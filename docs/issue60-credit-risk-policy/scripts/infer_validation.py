"""#20 최종 모델 번들로 기존 합성 데이터에 대한 추론만 수행한다 (Issue #60 정책 시뮬레이션의 입력).

실행 (ai 저장소의 가상환경 사용, ai 저장소에는 아무것도 쓰지 않는다):
    set PYTHONDONTWRITEBYTECODE=1
    <ai>\\.venv\\Scripts\\python docs\\issue60-credit-risk-policy\\scripts\\infer_validation.py [--ai <ai 저장소 경로>]

하는 일
  1. 독립 검증 데이터 (#20, 생성 seed 100~109 x D1/D2/D3, 각 1,000계정)를 서버와 같은 경로
     (inference.assess_batch) 로 추론해 계정별 결과를 저장한다.          -> output/inference_per_account.csv.gz
     #20 의 output/independent_runs.csv (TP/FP/FN/TN) 와 일치하는지 확인한다.
  2. 참고 분석용: #17 이 저장한 기준 조건 표 (생성 seed 0~9, 모델 "선정" 에 쓴 데이터) 에는 v1 분산과 v2 분산이
     함께 있다. 같은 번들로 추론해 따로 저장한다. 독립 검증 결과와 섞지 않는다. -> output/reference_issue17_inference.csv.gz

하지 않는 일: 모델 학습, 임계값 재계산, 데이터 생성, ai 저장소 파일 수정.
"""
import argparse
import hashlib
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "output"
DISTS = ("D1", "D2", "D3")
FEATURES = ["avg_booking_interval", "booking_interval_variance", "cancel_rate", "card_reuse_count", "ip_booking_density", "booking_count"]


def payloads(table, specs):
    out = []
    for row in table.itertuples(index=False):
        features = {}
        for name, spec in specs.items():
            value = getattr(row, name)
            features[spec["camel"]] = None if pd.isna(value) else (int(value) if spec["integer"] else float(value))
        out.append({"userId": str(row.user_id), "calculatedAt": "2026-11-23T00:00:00Z", "windowDays": 30, "features": features})
    return out


def score(table, bundle, assess_batch, specs):
    results = assess_batch(payloads(table, specs), bundle)
    return pd.DataFrame({
        "status": [r["status"] for r in results],
        "raw_anomaly_score": [r["rawAnomalyScore"] for r in results],
        "anomaly_score": [r["anomalyScore"] for r in results],
        "is_flagged": [r["isAnomaly"] for r in results],
        "model_version": [r["modelVersion"] for r in results],
    })


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ai", default=str(HERE.parents[3] / "ai"))
    ai = Path(parser.parse_args().ai).resolve()
    sys.path.insert(0, str(ai))
    from inference import assess_batch, load_bundle  # noqa: E402  (읽기 전용 import)
    from preprocessing import FEATURE_SPECS  # noqa: E402

    model_path = ai / "models" / "behavior_model.joblib"
    bundle = load_bundle(model_path)
    print("model", bundle["model_version"], "sha256", hashlib.sha256(model_path.read_bytes()).hexdigest()[:16], "raw_threshold", bundle["raw_threshold"])
    OUT.mkdir(parents=True, exist_ok=True)

    # ---- 1. 독립 검증 데이터 (#20) ----
    data = ai / "experiments" / "issue20-final-model" / "data"
    runs = pd.read_csv(ai / "experiments" / "issue20-final-model" / "output" / "independent_runs.csv", encoding="utf-8-sig")
    parts = []
    for seed in range(100, 110):
        for dist in DISTS:
            table = pd.read_csv(data / f"{dist}__seed{seed}.csv.gz")
            table["tool_profile"] = table["tool_profile"].fillna("")
            result = score(table, bundle, assess_batch, FEATURE_SPECS)
            frame = pd.concat([table.assign(dist=dist, generation_seed=seed), result], axis=1)
            y, flagged = frame["is_anomaly"].to_numpy() == 1, frame["is_flagged"].to_numpy(dtype=bool)
            expected = runs[(runs["evaluated_on"] == dist) & (runs["generation_seed"] == seed)].iloc[0]
            got = (int((flagged & y).sum()), int((flagged & ~y).sum()), int((~flagged & y).sum()), int((~flagged & ~y).sum()))
            assert got == (expected["TP"], expected["FP"], expected["FN"], expected["TN"]), (dist, seed, got)
            parts.append(frame)
    everything = pd.concat(parts, ignore_index=True)
    assert (everything["status"] == "OK").all() and len(everything) == 30000
    assert ((everything["anomaly_score"] >= 0.5) == everything["is_flagged"].astype(bool)).all()
    columns = ["dist", "generation_seed", "user_id", "scenario", "is_anomaly", "tool_profile"] + FEATURES + ["status", "raw_anomaly_score", "anomaly_score", "is_flagged", "model_version"]
    everything[columns].to_csv(OUT / "inference_per_account.csv.gz", index=False, encoding="utf-8", lineterminator="\n", compression="gzip")
    print("independent validation: 30 datasets, 30000 accounts, confusion counts match #20 independent_runs.csv")

    # ---- 2. 참고: #17 기준 조건 표 (seed 0~9, 모델 선정에 쓴 데이터). v1/v2 분산이 함께 있다 ----
    runs17 = ai / "experiments" / "issue17-event-based-dummy" / "data" / "v4" / "_runs"
    parts = []
    for seed in range(10):
        for dist in DISTS:
            path = runs17 / f"{dist}__base__seed{seed}.csv.gz"
            if not path.exists():
                print("missing", path.name)
                continue
            src = pd.read_csv(path)
            table = pd.DataFrame({
                "user_id": src["user_id"], "scenario": src["scenario"], "is_anomaly": src["is_anomaly"],
                "avg_booking_interval": src["avg_booking_interval__d30__mean_var"].to_numpy(dtype=float),
                "booking_interval_variance": src["booking_interval_variance__d30__mean_var__min5"].to_numpy(dtype=float),  # v2 (모델 입력)
                "cancel_rate": src["cancel_rate"], "card_reuse_count": src["card_reuse_count"],
                "ip_booking_density": src["ip_booking_density"], "booking_count": src["booking_count"],
            })
            result = score(table, bundle, assess_batch, FEATURE_SPECS)
            frame = pd.concat([table.assign(dist=dist, generation_seed=seed,
                                            booking_interval_variance_v1=src["booking_interval_variance__d30__mean_var"].to_numpy(dtype=float),
                                            n_gaps=src["n_gaps__d30"].to_numpy()), result], axis=1)
            parts.append(frame)
    if parts:
        reference = pd.concat(parts, ignore_index=True)
        reference.to_csv(OUT / "reference_issue17_inference.csv.gz", index=False, encoding="utf-8", lineterminator="\n", compression="gzip")
        print("reference (#17 seeds 0-9):", len(reference), "accounts,", reference.groupby(["dist", "generation_seed"]).ngroups, "datasets, status", reference["status"].value_counts().to_dict())


if __name__ == "__main__":
    main()
