// 자동 업데이트 + 오프라인 지원 서비스워커 (STEP13)
const VERSION = "v112-2026-07-18";   // ★ 배포마다 변경 — 새 버전 감지용
const SHELL = [
  "./",
  "./index.html",
  "./manifest.json",
  "./icon-192.png",
  "./icon-512.png",
];

self.addEventListener("install", (e) => {
  // 앱 셸을 미리 캐시 → 오프라인에서도 첫 화면이 뜬다. 그리고 바로 대기 건너뛰기.
  e.waitUntil(
    caches.open(VERSION).then((cache) => Promise.all(
      SHELL.map((u) => cache.add(u).catch(() => {}))   // 일부 실패해도 설치는 계속
    )).then(() => self.skipWaiting())
  );
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
  if (e.data && e.data.type === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;   // 쓰기 요청(Firebase 등)은 그대로 통과

  // 페이지 내비게이션: 네트워크 우선(최신), 실패하면 캐시된 index.html로 오프라인 진입.
  if (req.mode === "navigate") {
    e.respondWith(
      fetch(req).then((res) => {
        const copy = res.clone();
        caches.open(VERSION).then((c) => c.put("./index.html", copy)).catch(() => {});
        return res;
      }).catch(() => caches.match("./index.html").then((r) => r || caches.match(req)))
    );
    return;
  }

  // 그 외 정적 자원: 네트워크 우선(최신 파일) + 캐시 폴백(오프라인).
  e.respondWith(
    fetch(req).then((res) => {
      const copy = res.clone();
      caches.open(VERSION).then((cache) => cache.put(req, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match(req))
  );
});

// ── Background Sync: 오프라인에서 밀린 작업을 온라인 복귀 시 앱이 처리하도록 신호만 보냄.
//    (실제 데이터 쓰기는 앱/Firebase가 담당 — SW는 트리거 역할만)
self.addEventListener("sync", (e) => {
  if (e.tag === "joassam-sync") {
    e.waitUntil(
      self.clients.matchAll({ includeUncontrolled: true }).then((clients) => {
        clients.forEach((c) => c.postMessage({ type: "BG_SYNC", tag: "joassam-sync" }));
      })
    );
  }
});

// ── Push 수신(향후 서버 연결 대비): 페이로드가 있으면 알림 표시.
self.addEventListener("push", (e) => {
  let data = {};
  try { data = e.data ? e.data.json() : {}; } catch (err) { data = { body: (e.data && e.data.text()) || "" }; }
  const title = data.title || "조아저씨쌈";
  const opts = { body: data.body || "새 알림이 있어요", icon: "./icon-192.png", badge: "./icon-192.png", data: data };
  e.waitUntil(self.registration.showNotification(title, opts));
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: "window" }).then((cs) => {
    for (const c of cs) { if ("focus" in c) return c.focus(); }
    if (self.clients.openWindow) return self.clients.openWindow("./index.html");
  }));
});
