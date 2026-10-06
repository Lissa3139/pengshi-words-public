(() => {
  const repo = "Lissa3139/pengshi-words-public";
  const latestUrl = "https://github.com/" + repo + "/releases/latest";
  const assetPrefix = "/" + repo + "/releases/download/";
  const $ = (selector) => document.querySelector(selector);
  const year = $("#current-year");
  if (year) year.textContent = String(new Date().getFullYear());

  const labels = { msi: "下载 Windows 版", exe: "下载 Windows EXE", apk: "下载 Android 版" };
  const primary = $("#primary-download");
  const primaryLabel = $("#primary-download-label");
  const toggle = $("#download-toggle");
  const menu = $("#download-menu");
  const releaseMeta = $("#release-meta");
  const choices = Object.fromEntries(["msi", "exe", "apk"].map((kind) => [kind, $('[data-download="' + kind + '"]')]));
  let preferred = /Android/i.test(navigator.userAgent) || window.matchMedia("(max-width: 620px)").matches ? "apk" : "msi";
  let releaseAssets = {};

  function openMenu(open) {
    menu.hidden = !open;
    toggle.setAttribute("aria-expanded", String(open));
    if (open) choices.msi.focus();
  }
  toggle.addEventListener("click", () => openMenu(menu.hidden));
  document.addEventListener("click", (event) => {
    if (!$("#download").contains(event.target)) openMenu(false);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !menu.hidden) { openMenu(false); toggle.focus(); }
  });
  Object.entries(choices).forEach(([kind, link]) => {
    link.addEventListener("click", () => {
      preferred = kind;
      updatePrimary();
      openMenu(false);
    });
  });
  function updatePrimary() {
    primaryLabel.textContent = labels[preferred];
    primary.href = releaseAssets[preferred] || latestUrl;
    primary.setAttribute("aria-label", labels[preferred] + "，打开 GitHub 下载");
  }
  updatePrimary();

  function assetKind(name) {
    const filename = String(name || "").toLowerCase();
    if (filename.endsWith(".apk")) return "apk";
    if (filename.endsWith(".msi")) return "msi";
    if (filename.endsWith(".exe")) return "exe";
    return "";
  }
  function safeAsset(url) {
    try {
      const parsed = new URL(url);
      return parsed.protocol === "https:" && parsed.hostname === "github.com" && parsed.pathname.startsWith(assetPrefix);
    } catch { return false; }
  }
  async function loadRelease() {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch("https://api.github.com/repos/" + repo + "/releases/latest", {
        headers: { Accept: "application/vnd.github+json" },
        cache: "no-store",
        signal: controller.signal,
      });
      if (!response.ok) throw new Error("GitHub API " + response.status);
      const release = await response.json();
      const assets = Array.isArray(release.assets) ? release.assets : [];
      for (const asset of assets) {
        const kind = assetKind(asset.name);
        if (kind && !releaseAssets[kind] && safeAsset(asset.browser_download_url)) {
          releaseAssets[kind] = asset.browser_download_url;
        }
      }
      for (const [kind, link] of Object.entries(choices)) {
        link.href = releaseAssets[kind] || latestUrl;
        link.target = "_blank";
        link.rel = "noreferrer";
        if (!releaseAssets[kind]) link.querySelector("small").textContent = "前往 Release 页面查看";
      }
      const tag = String(release.tag_name || "").trim();
      releaseMeta.textContent = tag
        ? "最新公开版本 " + tag + " · 下载来自 GitHub Releases"
        : "下载来自 GitHub Releases";
      updatePrimary();
    } catch {
      releaseMeta.textContent = "暂时无法读取最新附件 · 点击下载可打开 GitHub Releases";
    } finally {
      clearTimeout(timeout);
    }
  }
  loadRelease();

  const pages = [
    { key: "home", label: "首页", title: "今天的任务，一眼看清", description: "复习、新词与每日额度集中呈现。", alt: "首页，显示今日学习任务" },
    { key: "decks", label: "词库", title: "从适合自己的词库开始", description: "浏览内置词库与自己导入的词表。", alt: "词库列表，展示可用词库" },
    { key: "selection", label: "选词仪表盘", title: "让新词来源更合适", description: "按词库设置参与范围与比例。", alt: "选词仪表盘，展示词库参与设置" },
    { key: "study-answer", label: "学习", title: "专注眼前这一词", description: "查看释义、例句，再给出自己的记忆反馈。", alt: "学习页，显示单词、释义与例句" },
    { key: "stats-retention", androidKey: "stats-forgetting", label: "遗忘曲线", title: "看见记忆随时间变化", description: "基于演示学习记录绘出的个人遗忘曲线。", alt: "统计页的遗忘曲线视图" },
    { key: "stats-learning", label: "学习情况", title: "回看每天的积累", description: "从学习记录中查看每天的反馈与进度。", alt: "统计页的学习情况视图" },
    { key: "stats-memory", label: "记忆持久度", title: "了解词汇记忆持久度", description: "从学习反馈估计不同持久度的词量。", alt: "统计页的记忆持久度视图" },
    { key: "settings", label: "设置", title: "学习方式由你掌握", description: "管理词库、备份、同步与个人偏好。", alt: "设置页，展示学习与数据选项" },
  ];
  const gallery = $("#gallery");
  const image = $("#gallery-image");
  const viewport = $("#gallery-viewport");
  const dots = $("#gallery-dots");
  const count = $("#gallery-count");
  const overline = $("#gallery-overline");
  const title = $("#gallery-title");
  const description = $("#gallery-description");
  let platform = "windows";
  let index = 0;

  pages.forEach((page, number) => {
    const dot = document.createElement("button");
    dot.type = "button";
    dot.setAttribute("aria-label", "查看第 " + (number + 1) + " 张：" + page.label);
    dot.addEventListener("click", () => show(number));
    dots.append(dot);
  });
  function show(number) {
    index = (number + pages.length) % pages.length;
    const page = pages[index];
    const key = platform === "android" ? page.androidKey || page.key : page.key;
    image.src = "images/site/screens/" + platform + "-" + key + ".png";
    image.alt = (platform === "android" ? "Android " : "Windows ") + page.alt;
    image.width = platform === "android" ? 1080 : 1280;
    image.height = platform === "android" ? 2400 : 820;
    gallery.dataset.platform = platform;
    count.textContent = String(index + 1).padStart(2, "0") + " / " + String(pages.length).padStart(2, "0");
    overline.textContent = String(index + 1).padStart(2, "0") + " / " + page.label;
    title.textContent = page.title;
    description.textContent = page.description;
    [...dots.children].forEach((dot, item) => {
      dot.classList.toggle("is-active", item === index);
      if (item === index) dot.setAttribute("aria-current", "true");
      else dot.removeAttribute("aria-current");
    });
  }
  $("#gallery-prev").addEventListener("click", () => show(index - 1));
  $("#gallery-next").addEventListener("click", () => show(index + 1));
  document.querySelectorAll("[data-platform]").forEach((button) => {
    button.addEventListener("click", () => {
      platform = button.dataset.platform;
      document.querySelectorAll("[data-platform]").forEach((item) => {
        const active = item === button;
        item.classList.toggle("is-active", active);
        item.setAttribute("aria-pressed", String(active));
      });
      show(index);
    });
  });
  viewport.addEventListener("keydown", (event) => {
    if (event.key === "ArrowLeft") { show(index - 1); event.preventDefault(); }
    if (event.key === "ArrowRight") { show(index + 1); event.preventDefault(); }
  });
  let touchStart = null;
  viewport.addEventListener("touchstart", (event) => {
    if (event.touches.length === 1) touchStart = { x: event.touches[0].clientX, y: event.touches[0].clientY };
  }, { passive: true });
  viewport.addEventListener("touchend", (event) => {
    if (!touchStart || event.changedTouches.length !== 1) return;
    const dx = event.changedTouches[0].clientX - touchStart.x;
    const dy = event.changedTouches[0].clientY - touchStart.y;
    if (Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy) * 1.3) show(index + (dx < 0 ? 1 : -1));
    touchStart = null;
  }, { passive: true });
  show(0);
})();
