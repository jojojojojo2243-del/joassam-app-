// 자동 업데이트 지원 서비스워커
const VERSION = "v79-2026-07-13";   // ★ 배포마다 변경 — 새 버전 감지용

self.addEventListener("install", (e) => {
  // 새 버전 설치 시 바로 대기 상태를 건너뛸 준비
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  // 활성화되면 즉시 모든 페이지 제어권 가져오기 + 옛 캐시 정리
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// 앱에서 "SKIP_WAITING" 메시지를 보내면 즉시 새 버전 적용
self.addEventListener("message", (e) => {
  if (e.data && e.data.type === "SKIP_WAITING") {
    self.skipWaiting();
  }
});

self.addEventListener("fetch", (e) => {
  // 항상 네트워크 우선(최신 파일). 오프라인일 때만 캐시 사용.
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        // 받은 최신 파일을 캐시에 저장(오프라인 대비)
        const copy = res.clone();
        caches.open(VERSION).then((cache) => cache.put(e.request, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});
