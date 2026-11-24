"""Issue #60: Credit 감점식과 위험 정책 후보(A/B/C/D)를 같은 계정·같은 AI 추론 결과에서 비교한다.

    <ai>\\.venv\\Scripts\\python docs\\issue60-credit-risk-policy\\scripts\\simulate_policy.py

입력: output/inference_per_account.csv.gz (infer_validation.py 가 만든 계정별 추론 결과. 여기서 추론·학습하지 않는다)
백엔드 코드를 그대로 옮긴 부분 (ticket-fds-admin/backend, 2026-11-24 feat/#60 기준)
  CreditCalculator.calculate          BaseCredit, DynamicPenalty = min(70, AI + min(30, Rule)), round, 등급 40/20
  CreditEvaluationService.calculateRulePenalty   카드 2~3계정 5 / 4계정 이상 15, IP 5, 분산 < 1.0 이면 5, 합계 최대 30
  PolicyDecisionService.evaluatePolicy          RESTRICT -> DELAY -> REQUIRE_CAPTCHA -> ALLOW 순서, AND/OR 그대로

정책 조건
  A  기존                     AI 감점 = 40 * s                    CAPTCHA 기준 0.40
  B  AI 감점식만 변경          AI 감점 = 40 * max(0, 2s - 1)       CAPTCHA 기준 0.40
  C  CAPTCHA 기준만 변경       AI 감점 = 40 * s                    CAPTCHA 기준 0.50
  D  둘 다 변경               AI 감점 = 40 * max(0, 2s - 1)       CAPTCHA 기준 0.50
  DELAY 0.70, RESTRICT 0.85 + Credit < 20, Credit 등급 40/20 은 네 조건에서 같다.

시뮬레이션 가정 (데이터에 없는 값. 보고서 "가정" 절과 같다)
  G1 관람 이력 없음 (A=N=C=0, BaseCredit 50) 이 기본. 이력이 있는 조건은 모든 계정에 같은 이력을 준 비교다.
  G2 카드 감점: user_card 가 데이터에 없어 card_reuse_count(예매 이벤트 기준)를 대용으로 쓴다. 제외한 결과도 낸다.
  G3 IP 집중도 감점(최근 10분 같은 IP 시도 5회 이상): 재현하지 못한다. 기본 0. ip_booking_density 로 대체하지 않는다.
     "ip5" 는 모든 계정에 5점을 더한 민감도 조건이며 실제 IP 감점 여부가 아니다.
  G4 분산 감점: 요청의 intervalVariance 가 Feature 정의 v2 의 bookingIntervalVariance(간격 5개 미만이면 null)와 같다고 본다.
  G5 평가 시점: AI 추론 -> Credit 평가 -> 정책 결정이 같은 Feature 로 한 번씩 일어난다고 본다.

합성 데이터의 값이다. 실제 운영 성능이 아니다. 독립 검증 데이터를 정책 비교에 다시 쓴 것이므로 선정한 정책의 독립 검증도 아니다.
"""
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "output"

# ---- CreditPolicyConstants ----
WEIGHT_COMPLETED, WEIGHT_NO_SHOW, WEIGHT_AUTO_CANCEL = 1.0, 2.0, 1.5
BAYESIAN_ALPHA, BAYESIAN_TOTAL_SMOOTHING = 5.0, 10.0
WEIGHT_ANOMALY_SCORE, MAX_RULE_PENALTY, MAX_TOTAL_PENALTY = 40.0, 30.0, 70.0
PENALTY_CARD_SHARING_HIGH, PENALTY_CARD_SHARING_MID, PENALTY_IP_BURST, PENALTY_LOW_INTERVAL_VARIANCE = 15.0, 5.0, 5.0, 5.0
THRESHOLD_NORMAL, THRESHOLD_SUSPICIOUS = 40, 20
# ---- PolicyDecisionService ----
CUT_RESTRICT, CUT_DELAY = 0.85, 0.70

POLICIES = {  # 이름: (AI 감점식, CAPTCHA 이상 점수 기준)
    "A": ("linear", 0.40),
    "B": ("above_threshold", 0.40),
    "C": ("linear", 0.50),
    "D": ("above_threshold", 0.50),
}
NORMAL = ["normal", "fast_normal", "shared_ip", "frequent_cancel"]
MACRO = ["fast", "jitter", "multi_account", "hold_cancel"]
MACRO_LABEL = {"fast": "고정 간격", "jitter": "랜덤 지연", "multi_account": "다중 계정", "hold_cancel": "선점·취소"}
GRADES = ["NORMAL", "SUSPICIOUS", "HIGH_RISK"]
ACTIONS = ["ALLOW", "REQUIRE_CAPTCHA", "DELAY", "RESTRICT"]
DISTS = ["D1", "D2", "D3"]


def java_round(x):
    """Java Math.round(double): floor(x + 0.5)."""
    return np.floor(np.asarray(x, dtype=float) + 0.5).astype(int)


def base_credit(completed, no_show, auto_cancelled):
    a, n, c = completed * WEIGHT_COMPLETED, no_show * WEIGHT_NO_SHOW, auto_cancelled * WEIGHT_AUTO_CANCEL
    return 100.0 * (a + BAYESIAN_ALPHA) / (a + n + c + BAYESIAN_TOTAL_SMOOTHING)


def ai_penalty(score, formula):
    """score 가 NaN(평가 없음)이면 CreditCalculator 와 같이 0 으로 본다."""
    s = np.clip(np.where(np.isnan(score), 0.0, score), 0.0, 1.0)
    if formula == "linear":
        return s * WEIGHT_ANOMALY_SCORE
    return WEIGHT_ANOMALY_SCORE * np.maximum(0.0, 2.0 * s - 1.0)


def rule_penalty(card_accounts, ip_burst, variance):
    """card_accounts: 카드 공유 계정 수(대용: card_reuse_count), ip_burst: IP 규칙에 걸렸는지, variance: null 이면 NaN."""
    card = np.where(card_accounts >= 4, PENALTY_CARD_SHARING_HIGH, np.where(card_accounts >= 2, PENALTY_CARD_SHARING_MID, 0.0))
    ip = np.where(ip_burst, PENALTY_IP_BURST, 0.0)
    var = np.where(~np.isnan(variance) & (variance < 1.0), PENALTY_LOW_INTERVAL_VARIANCE, 0.0)
    return np.minimum(MAX_RULE_PENALTY, card + ip + var), card, ip, var


def grade_of(credit):
    return np.where(credit >= THRESHOLD_NORMAL, "NORMAL", np.where(credit >= THRESHOLD_SUSPICIOUS, "SUSPICIOUS", "HIGH_RISK"))


def evaluate_policy(credit, score, captcha_cut):
    """PolicyDecisionService.evaluatePolicy. score 가 NaN 이면 'AI 분석 이력 없음' 분기."""
    no_ai = np.isnan(score)
    s = np.where(no_ai, 0.0, score)
    with_ai = np.where((credit < 20) & (s >= CUT_RESTRICT), "RESTRICT",
              np.where((credit < 20) | (s >= CUT_DELAY), "DELAY",
              np.where((credit < 40) | (s >= captcha_cut), "REQUIRE_CAPTCHA", "ALLOW")))
    without_ai = np.where(credit >= 40, "ALLOW", np.where(credit >= 20, "REQUIRE_CAPTCHA", "DELAY"))
    return np.where(no_ai, without_ai, with_ai)


def simulate(frame, policy, history=(0, 0, 0), card=True, ip_all=False, variance_column="booking_interval_variance"):
    formula, captcha_cut = POLICIES[policy]
    score = frame["anomaly_score"].to_numpy(dtype=float)
    base = base_credit(*history)
    ai = ai_penalty(score, formula)
    card_accounts = frame["card_reuse_count"].to_numpy() if card else np.ones(len(frame))
    rule, card_pen, ip_pen, var_pen = rule_penalty(card_accounts, np.full(len(frame), ip_all), frame[variance_column].to_numpy(dtype=float))
    dynamic = np.minimum(MAX_TOTAL_PENALTY, ai + rule)
    credit = java_round(np.clip(base - dynamic, 0.0, 100.0))
    action = evaluate_policy(credit, score, captcha_cut)
    out = frame[["dist", "generation_seed", "scenario", "is_anomaly"]].copy()
    out["score"], out["ai_penalty"], out["rule_penalty"], out["dynamic_penalty"] = score, ai, rule, dynamic
    out["card_penalty"], out["variance_penalty"] = card_pen, var_pen
    out["credit"], out["grade"], out["action"] = credit, grade_of(credit), action
    out["base_grade"] = str(grade_of(java_round(base)))
    out["credit_drop"] = java_round(base) - credit
    # 제재의 원인 분해 (Credit 조건 / 이상 점수 조건)
    out["captcha_by_credit_only"] = (action == "REQUIRE_CAPTCHA") & (credit < 40) & ~(score >= captcha_cut)
    out["captcha_by_score_only"] = (action == "REQUIRE_CAPTCHA") & ~(credit < 40) & (score >= captcha_cut)
    out["captcha_by_both"] = (action == "REQUIRE_CAPTCHA") & (credit < 40) & (score >= captcha_cut)
    out["delay_by_credit_only"] = (action == "DELAY") & (credit < 20) & ~(score >= CUT_DELAY)
    out["delay_by_score"] = (action == "DELAY") & (score >= CUT_DELAY)
    out["ai_and_rule_both"] = (ai > 0) & (rule > 0)
    return out


def summarize(result):
    """한 집단의 지표 1행."""
    row = {"accounts": len(result),
           "penalty_mean": result["dynamic_penalty"].mean(), "penalty_median": result["dynamic_penalty"].median(),
           "ai_penalty_mean": result["ai_penalty"].mean(), "rule_penalty_mean": result["rule_penalty"].mean(),
           "credit_drop_mean": result["credit_drop"].mean(), "credit_drop_median": result["credit_drop"].median(),
           "credit_mean": result["credit"].mean(), "credit_median": result["credit"].median(),
           "share_penalty_gt0": (result["dynamic_penalty"] > 0).mean()}
    for grade in GRADES:
        row[f"grade_{grade}"] = (result["grade"] == grade).mean()
    rank = {g: i for i, g in enumerate(GRADES)}
    row["grade_dropped"] = (result["grade"].map(rank) > result["base_grade"].map(rank)).mean()
    for action in ACTIONS:
        row[f"action_{action}"] = (result["action"] == action).mean()
    row["action_DELAY_or_RESTRICT"] = result["action"].isin(["DELAY", "RESTRICT"]).mean()
    row["action_any_sanction"] = (result["action"] != "ALLOW").mean()
    for column in ("captcha_by_credit_only", "captcha_by_score_only", "captcha_by_both", "delay_by_credit_only", "delay_by_score", "ai_and_rule_both"):
        row[column] = result[column].mean()
    both = result[result["ai_and_rule_both"]]
    row["both_ai_penalty_mean"] = both["ai_penalty"].mean() if len(both) else np.nan
    row["both_rule_penalty_mean"] = both["rule_penalty"].mean() if len(both) else np.nan
    return row


def groups(result):
    yield "정상 전체", result[result["is_anomaly"] == 0]
    for name in NORMAL:
        yield name, result[result["scenario"] == name]
    yield "매크로 전체", result[result["is_anomaly"] == 1]
    for name in MACRO:
        yield name, result[result["scenario"] == name]


SETTINGS = {  # 이름: simulate 인자
    "base": {},                                  # 신규 사용자, 카드 대용 변수 사용, IP 감점 0, 분산 v2
    "ip5": {"ip_all": True},                     # 민감도: 모든 계정에 IP 감점 5 (가정)
    "nocard": {"card": False},                   # 민감도: 카드 감점 제외
    "hist_A5": {"history": (5, 0, 0)},           # 정상 관람 5회 (BaseCredit 66.7)
    "hist_A10": {"history": (10, 0, 0)},         # 정상 관람 10회 (BaseCredit 75)
    "hist_N1": {"history": (0, 1, 0)},           # 노쇼 1회 (BaseCredit 41.7)
    "hist_N2": {"history": (0, 2, 0)},           # 노쇼 2회 (BaseCredit 35.7)
}


def parity_checks():
    """기존 Java 테스트(CreditCalculatorTest, PolicyDecisionService 분기)의 기대값과 맞는지 확인한다."""
    lines = []

    def credit(history, score, rule, formula="linear"):
        dynamic = min(MAX_TOTAL_PENALTY, float(ai_penalty(np.array([score]), formula)[0]) + min(MAX_RULE_PENALTY, rule))
        return int(java_round(np.clip(base_credit(*history) - dynamic, 0, 100))), dynamic

    cases = [  # (이력, score, rule, 기대 Credit, 기대 감점)  CreditCalculatorTest 시나리오 1~5, 경계값
        ((0, 0, 0), 0.0, 0.0, 50, 0.0), ((0, 0, 0), 0.0, 5.0, 45, 5.0), ((0, 0, 0), 0.5, 0.0, 30, 20.0),
        ((0, 0, 0), 0.75, 15.0, 5, 45.0), ((10, 0, 0), 0.0, 5.0, 70, 5.0), ((0, 5, 2), 1.0, 30.0, 0, 70.0)]
    for history, score, rule, want_credit, want_penalty in cases:
        got = credit(history, score, rule)
        assert got == (want_credit, want_penalty), (history, score, rule, got)
        lines.append(f"credit  history={history} score={score} rule={rule} -> credit {got[0]}, penalty {got[1]}  OK")
    assert int(java_round(55.5)) == 56 and int(java_round(39.5)) == 40 and int(java_round(19.5)) == 20
    lines.append("round   Math.round(39.5)=40, Math.round(19.5)=20  OK")
    policy_cases = [(19, 0.85, "RESTRICT"), (19, 0.84, "DELAY"), (20, 0.85, "DELAY"), (50, 0.70, "DELAY"), (19, 0.0, "DELAY"),
                    (39, 0.0, "REQUIRE_CAPTCHA"), (50, 0.40, "REQUIRE_CAPTCHA"), (50, 0.39, "ALLOW"), (40, 0.0, "ALLOW"),
                    (50, np.nan, "ALLOW"), (39, np.nan, "REQUIRE_CAPTCHA"), (19, np.nan, "DELAY")]
    for c, s, want in policy_cases:
        got = str(evaluate_policy(np.array([c]), np.array([s]), 0.40)[0])
        assert got == want, (c, s, got)
        lines.append(f"policy  credit={c} score={s} cut=0.40 -> {got}  OK")
    assert str(evaluate_policy(np.array([50]), np.array([0.45]), 0.50)[0]) == "ALLOW"
    for s, want in [(0.0, 0.0), (0.5, 0.0), (0.75, 20.0), (1.0, 40.0), (0.499999, 0.0)]:
        got = float(ai_penalty(np.array([s]), "above_threshold")[0])
        assert abs(got - want) < 1e-9, (s, got)
        lines.append(f"new AI penalty  score={s} -> {got}  OK")
    (OUT / "java_parity_check.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    parity_checks()
    data = pd.read_csv(OUT / "inference_per_account.csv.gz")
    assert len(data) == 30000 and (data["status"] == "OK").all() and data["model_version"].nunique() == 1
    # 분산이 있으면 유효 간격 5개 이상 (= 시도 6건 이상). 간격 수 = 시도 수 - 1
    present = data["booking_interval_variance"].notna()
    assert (data.loc[present, "booking_count"] - 1 >= 5).all() and (data.loc[~present, "booking_count"] - 1 < 5).all()

    rows, seed_rows, results = [], [], {}
    for setting, kwargs in SETTINGS.items():
        for policy in POLICIES:
            result = simulate(data, policy, **kwargs)
            results[(setting, policy)] = result
            for dist in DISTS + ["전체"]:
                part = result if dist == "전체" else result[result["dist"] == dist]
                for group, sub in groups(part):
                    rows.append({"setting": setting, "policy": policy, "dist": dist, "group": group, **summarize(sub)})
            if setting == "base":
                for (dist, seed), part in result.groupby(["dist", "generation_seed"]):
                    for group, sub in groups(part):
                        seed_rows.append({"policy": policy, "dist": dist, "generation_seed": seed, "group": group, "accounts": len(sub),
                                          **{f"action_{a}": (sub["action"] == a).mean() for a in ACTIONS},
                                          "grade_dropped": (sub["grade"] != "NORMAL").mean(), "penalty_mean": sub["dynamic_penalty"].mean()})
    summary = pd.DataFrame(rows)
    summary.to_csv(OUT / "sim_summary.csv", index=False, encoding="utf-8-sig")
    by_seed = pd.DataFrame(seed_rows)
    by_seed.to_csv(OUT / "sim_by_seed.csv", index=False, encoding="utf-8-sig")
    numeric = [c for c in by_seed.columns if c.startswith("action_") or c in ("grade_dropped", "penalty_mean")]
    spread = by_seed.groupby(["policy", "dist", "group"], sort=False)[numeric].agg(["mean", "std", "min", "max"])
    spread.columns = [f"{a}__{b}" for a, b in spread.columns]
    spread.reset_index().to_csv(OUT / "sim_seed_spread.csv", index=False, encoding="utf-8-sig")

    # ---- 같은 계정의 조치가 A 에서 다른 조건으로 어떻게 바뀌는가 (base) ----
    transitions = []
    base_a = results[("base", "A")]
    for policy in ("B", "C", "D"):
        other = results[("base", policy)]
        for group, sub in groups(base_a):
            table = pd.crosstab(sub["action"], other.loc[sub.index, "action"])
            for before in ACTIONS:
                for after in ACTIONS:
                    count = int(table.loc[before, after]) if before in table.index and after in table.columns else 0
                    if count:
                        transitions.append({"from_policy": "A", "to_policy": policy, "group": group, "action_A": before, f"action_other": after,
                                            "accounts": count, "share_of_group": count / len(sub)})
    pd.DataFrame(transitions).to_csv(OUT / "sim_action_transitions.csv", index=False, encoding="utf-8-sig")

    # ---- 이상 점수 구간별: 조건마다 Credit 과 조치 (신규 사용자, 규칙 감점 없음 / 5 / 15 / 20) ----
    grid = []
    for score in np.round(np.arange(0.0, 1.0001, 0.01), 2):
        for rule in (0.0, 5.0, 10.0, 15.0, 20.0):
            for policy, (formula, cut) in POLICIES.items():
                dynamic = min(MAX_TOTAL_PENALTY, float(ai_penalty(np.array([score]), formula)[0]) + rule)
                credit = int(java_round(np.clip(50.0 - dynamic, 0, 100)))
                grid.append({"anomaly_score": score, "rule_penalty": rule, "policy": policy, "credit": credit, "grade": str(grade_of(credit)),
                             "action": str(evaluate_policy(np.array([credit]), np.array([score]), cut)[0])})
    grid = pd.DataFrame(grid)
    grid.to_csv(OUT / "sim_score_grid_new_user.csv", index=False, encoding="utf-8-sig")
    boundaries = []
    for (policy, rule), part in grid.groupby(["policy", "rule_penalty"], sort=False):
        row = {"policy": policy, "rule_penalty": rule}
        for action in ("REQUIRE_CAPTCHA", "DELAY", "RESTRICT"):
            level = ACTIONS.index(action)
            hit = part[part["action"].map(ACTIONS.index) >= level]
            row[f"min_score_for_{action}_or_stronger"] = hit["anomaly_score"].min() if len(hit) else np.nan
        boundaries.append(row)
    pd.DataFrame(boundaries).to_csv(OUT / "sim_score_boundaries_new_user.csv", index=False, encoding="utf-8-sig")

    # ---- 이상 점수 분포 (참고) ----
    quantiles = []
    for dist in DISTS + ["전체"]:
        part = data if dist == "전체" else data[data["dist"] == dist]
        for group, sub in groups(part.assign(dist=part["dist"])):
            s = sub["anomaly_score"]
            quantiles.append({"dist": dist, "group": group, "accounts": len(sub), **{f"p{int(q*100):02d}": s.quantile(q) for q in (0.05, 0.25, 0.5, 0.75, 0.9, 0.95)},
                              "share_ge_0.2625": (s >= 0.2625).mean(), "share_ge_0.40": (s >= 0.40).mean(), "share_ge_0.50": (s >= 0.50).mean(),
                              "share_ge_0.70": (s >= 0.70).mean(), "share_ge_0.85": (s >= 0.85).mean(), "share_ge_0.875": (s >= 0.875).mean(),
                              "variance_present": sub["booking_interval_variance"].notna().mean(),
                              "variance_lt_1": (sub["booking_interval_variance"] < 1.0).mean(),
                              "card_2_3": sub["card_reuse_count"].between(2, 3).mean(), "card_ge_4": (sub["card_reuse_count"] >= 4).mean()})
    pd.DataFrame(quantiles).to_csv(OUT / "score_and_rule_inputs.csv", index=False, encoding="utf-8-sig")

    # ---- 참고 분석: 분산 v1 과 v2 (#17 의 seed 0~9 표. 모델 선정에 쓴 데이터. 독립 검증과 섞지 않는다) ----
    reference_path = OUT / "reference_issue17_inference.csv.gz"
    if reference_path.exists():
        ref = pd.read_csv(reference_path)
        v1, v2 = ref["booking_interval_variance_v1"], ref["booking_interval_variance"]
        ref_rows = []
        for group, sub in groups(ref):
            a, b = v1.loc[sub.index], v2.loc[sub.index]
            row = {"group": group, "accounts": len(sub), "v1_present": a.notna().mean(), "v2_present": b.notna().mean(),
                   "v1_rule_hit (v1 < 1.0)": (a < 1.0).mean(), "v2_rule_hit (v2 < 1.0)": (b < 1.0).mean(),
                   "hit_only_under_v1": ((a < 1.0) & ~(b < 1.0)).mean(), "v1_exactly_0_with_lt5_gaps": ((a == 0) & b.isna()).mean()}
            for policy in ("A", "D"):
                with_v1 = simulate(sub, policy, variance_column="booking_interval_variance_v1")
                with_v2 = simulate(sub, policy, variance_column="booking_interval_variance")
                for action in ACTIONS:
                    row[f"{policy}_v1rule_{action}"] = (with_v1["action"] == action).mean()
                    row[f"{policy}_v2rule_{action}"] = (with_v2["action"] == action).mean()
                row[f"{policy}_action_changed_by_v1_rule"] = (with_v1["action"].to_numpy() != with_v2["action"].to_numpy()).mean()
                row[f"{policy}_grade_dropped_v1rule"] = (with_v1["grade"] != "NORMAL").mean()
                row[f"{policy}_grade_dropped_v2rule"] = (with_v2["grade"] != "NORMAL").mean()
            ref_rows.append(row)
        pd.DataFrame(ref_rows).to_csv(OUT / "reference_variance_v1_v2.csv", index=False, encoding="utf-8-sig")

    # ---- 화면 출력 ----
    pd.set_option("display.width", 260, "display.max_columns", 60, "display.max_rows", 400)
    view = summary[(summary["setting"] == "base") & (summary["dist"] == "전체")]
    cols = ["policy", "group", "penalty_mean", "penalty_median", "grade_NORMAL", "grade_SUSPICIOUS", "grade_HIGH_RISK"] + [f"action_{a}" for a in ACTIONS]
    print(view[cols].round(3).to_string(index=False))


if __name__ == "__main__":
    main()
