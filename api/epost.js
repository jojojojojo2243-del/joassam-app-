// 우체국 배송조회 (키 없이): 우체국 공개 조회 페이지를 서버가 대신 읽어서 "배달완료" 여부만 돌려줍니다.
// 사용: /api/epost?no=송장번호,송장번호  (최대 20개)
// 결과: { results: [{ no, state: "delivered" | "transit" | "none" | "error", last: "마지막 처리현황" }] }
const BASE = "https://service.epost.go.kr/trace.RetrieveDomRigiTraceList.comm";

function textOf(html) {
  return String(html || "")
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/<[^>]+>/g, "\n")
    .replace(/[ \t\r]+/g, " ")
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean);
}

// HTML -> { state, last }. 표(td) 안의 처리현황 칸이 "배달완료"로 시작할 때만 완료로 봅니다.
function parseTrace(html, no) {
  const lines = textOf(html);
  const all = lines.join(" ");
  if (!all.includes(no)) {
    if (/조회\s*결과가\s*없|등기번호를\s*확인|존재하지\s*않|잘못된/.test(all)) return { state: "none", last: "" };
    return { state: "error", last: "송장번호가 결과에 없음" };
  }
  // 표의 칸 단위로 다시 뽑기
  const cells = [];
  const re = /<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/gi;
  let m;
  while ((m = re.exec(html))) {
    const t = m[1].replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/\s+/g, " ").trim();
    if (t) cells.push(t);
  }
  const delivered = cells.some((c) => /^배달\s*완료/.test(c) || /^배송\s*완료/.test(c));
  const statusCells = cells.filter((c) => /^(접수|발송|도착|집하|배달준비|배달\s*완료|배송\s*완료|미배달|인수|취급|통관|입고|출고|운송|상차|하차|반송|이동)/.test(c));
  const last = statusCells.length ? statusCells[statusCells.length - 1] : "";
  if (delivered) return { state: "delivered", last: last || "배달완료" };
  if (statusCells.length) return { state: "transit", last };
  return { state: "error", last: "처리현황을 찾지 못함" };
}

async function fetchOne(no) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 9000);
  try {
    const r = await fetch(`${BASE}?sid1=${no}&displayHeader=N`, {
      signal: ctrl.signal,
      headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36", "Accept-Language": "ko-KR,ko;q=0.9", Accept: "text/html" },
    });
    if (!r.ok) return { no, state: "error", last: "HTTP " + r.status };
    const buf = Buffer.from(await r.arrayBuffer());
    let html = new TextDecoder("utf-8").decode(buf);
    if ((html.match(/�/g) || []).length > 5) {
      try { html = new TextDecoder("euc-kr").decode(buf); } catch (e) {}
    }
    return Object.assign({ no }, parseTrace(html, no));
  } catch (e) {
    return { no, state: "error", last: String(e && e.name === "AbortError" ? "시간 초과" : e && e.message || e).slice(0, 80) };
  } finally {
    clearTimeout(timer);
  }
}

module.exports = async (req, res) => {
  try {
    const raw = String((req.query && req.query.no) || "");
    const nos = [...new Set(raw.split(",").map((s) => s.replace(/\D/g, "")).filter((s) => s.length >= 10 && s.length <= 14))].slice(0, 20);
    if (!nos.length) { res.status(400).json({ error: "송장번호가 없어요" }); return; }
    const results = [];
    let idx = 0;
    const worker = async () => { while (idx < nos.length) { const n = nos[idx++]; results.push(await fetchOne(n)); } };
    await Promise.all([worker(), worker(), worker(), worker()]);
    res.setHeader("Cache-Control", "s-maxage=300");
    res.status(200).json({ results });
  } catch (e) {
    res.status(500).json({ error: String(e && e.message || e) });
  }
};
module.exports.parseTrace = parseTrace;
