// 최소한의 서비스워커 (PWABuilder가 PWA로 인식하도록 하는 용도)
self.addEventListener("install", (e) => {
  self.skipWaiting();
});

self.addEventListener("fetch", (e) => {
  // 네트워크 우선, 오프라인이어도 에러 없이 통과
  e.respondWith(
    fetch(e.request).catch(() => caches.match(e.request))
  );
});
