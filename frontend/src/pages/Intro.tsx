import { ShieldCheck } from 'lucide-react'

export default function Intro() {
  return (
    <section className="intro">
      <div className="intro-hero">
        {/* 로고는 확정 전입니다. 확정되면 이 박스 안에 <img>를 넣으세요. */}
        <div className="logo-box" aria-label="로고 자리"><ShieldCheck size={44} /><span>로고</span></div>
        <h1>BTS를 소개합니다!</h1>
      </div>
      <p className="intro-lead">BTS(Bullyang Ticket Sniper)는 “암표 없는 세상”을 꿈꿉니다.</p>
      <ul className="intro-list">
        <li><b>계정 등급제</b>계정별 부정 거래 의심 점수에 따라 정상·의심·위험 등급으로 분류합니다.</li>
        <li><b>크레딧 산출</b>비정상적인 행동 양상을 수집하여 점수로 산출합니다.</li>
        <li><b>AI Feature 계산</b>최근 30일 동안의 사용자 행동을 AI로 계산하여 의심 점수에 반영합니다.</li>
        <li><b>등급별 제재</b>의심 계정에는 Captcha 인증을, 위험 계정에는 Captcha 인증 + 대기열 후순위를 적용합니다.</li>
      </ul>
    </section>
  )
}
