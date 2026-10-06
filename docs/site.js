(() => {
  const repository = "Lissa3139/pengshi-words-public";
  const repositoryUrl = "https://github.com/" + repository;
  const latestReleaseUrl = repositoryUrl + "/releases/latest";
  const assetPrefix = "/" + repository + "/releases/download/";

  const elements = {
    version: document.querySelector("#latest-version"),
    date: document.querySelector("#release-date"),
    stars: document.querySelector("#repo-stars"),
    downloads: document.querySelector("#release-downloads"),
    status: document.querySelector("#api-status"),
    checksum: document.querySelector("#checksum-link"),
    heroAndroid: document.querySelector("#hero-android-link"),
    heroWindows: document.querySelector("#hero-windows-link"),
    year: document.querySelector("#current-year"),
  };

  const downloadLinks = {
    apk: document.querySelector("#download-apk"),
    msi: document.querySelector("#download-msi"),
    exe: document.querySelector("#download-exe"),
  };

  const downloadCards = {
    apk: document.querySelector('[data-download-card="apk"]'),
    msi: document.querySelector('[data-download-card="msi"]'),
    exe: document.querySelector('[data-download-card="exe"]'),
  };

  if (elements.year) elements.year.textContent = String(new Date().getFullYear());

  function formatNumber(value) {
    return new Intl.NumberFormat("zh-CN").format(Number(value) || 0);
  }

  function formatSize(bytes) {
    const size = Number(bytes);
    if (!Number.isFinite(size) || size <= 0) return "";
    return size >= 1024 * 1024
      ? (size / (1024 * 1024)).toFixed(1) + " MB"
      : Math.ceil(size / 1024) + " KB";
  }

  function formatDate(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    return new Intl.DateTimeFormat("zh-CN", {
      year: "numeric",
      month: "long",
      day: "numeric",
    }).format(date);
  }

  function trustedAssetUrl(value) {
    try {
      const url = new URL(value);
      return url.protocol === "https:"
        && url.hostname === "github.com"
        && url.pathname.startsWith(assetPrefix);
    } catch {
      return false;
    }
  }

  function assetKind(name) {
    const filename = String(name || "").toLowerCase();
    if (filename.endsWith(".apk")) return "apk";
    if (filename.endsWith(".msi")) return "msi";
    if (filename.endsWith(".exe")) return "exe";
    if (/^sha256sums(?:\.txt)?$/.test(filename)) return "checksum";
    return "";
  }

  async function loadJson(url) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(url, {
        headers: { Accept: "application/vnd.github+json" },
        cache: "no-store",
        signal: controller.signal,
      });
      if (!response.ok) throw new Error("GitHub API returned " + response.status);
      return await response.json();
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function updateAssetLink(kind, asset) {
    const link = downloadLinks[kind];
    const card = downloadCards[kind];
    if (!link || !card) return false;

    if (!asset || !trustedAssetUrl(asset.browser_download_url)) {
      card.hidden = true;
      return false;
    }

    link.href = asset.browser_download_url;
    link.target = "_blank";
    link.rel = "noreferrer";
    const label = link.querySelector(".download-link-label");
    if (label) {
      const size = formatSize(asset.size);
      label.textContent = "下载 " + kind.toUpperCase() + (size ? " · " + size : "");
    }
    card.hidden = false;
    return true;
  }

  function setFallback(message) {
    for (const card of Object.values(downloadCards)) {
      if (card) card.hidden = true;
    }
    if (elements.version) elements.version.textContent = "打开 GitHub Releases";
    if (elements.date) elements.date.textContent = "最新版本信息暂不可读取";
    if (elements.status) elements.status.textContent = message;
    if (elements.heroAndroid) elements.heroAndroid.href = latestReleaseUrl;
    if (elements.heroWindows) elements.heroWindows.href = latestReleaseUrl;
  }

  function applyRelease(release) {
    const assets = Array.isArray(release.assets) ? release.assets : [];
    const assetsByKind = new Map();
    for (const asset of assets) {
      const kind = assetKind(asset.name);
      if (kind && !assetsByKind.has(kind)) assetsByKind.set(kind, asset);
    }

    const tag = String(release.tag_name || "").trim();
    if (elements.version) elements.version.textContent = tag || "查看最新版本";
    if (elements.date) {
      const date = formatDate(release.published_at);
      elements.date.textContent = date ? "发布于 " + date : "来自 GitHub Releases";
    }

    const hasApk = updateAssetLink("apk", assetsByKind.get("apk"));
    const hasMsi = updateAssetLink("msi", assetsByKind.get("msi"));
    const hasExe = updateAssetLink("exe", assetsByKind.get("exe"));
    const windowsAsset = assetsByKind.get("msi") || assetsByKind.get("exe");

    if (elements.heroAndroid) {
      elements.heroAndroid.href = hasApk
        ? assetsByKind.get("apk").browser_download_url
        : latestReleaseUrl;
      elements.heroAndroid.target = "_blank";
      elements.heroAndroid.rel = "noreferrer";
    }
    if (elements.heroWindows) {
      elements.heroWindows.href = windowsAsset && trustedAssetUrl(windowsAsset.browser_download_url)
        ? windowsAsset.browser_download_url
        : latestReleaseUrl;
      elements.heroWindows.target = "_blank";
      elements.heroWindows.rel = "noreferrer";
    }

    const checksum = assetsByKind.get("checksum");
    if (elements.checksum) {
      if (checksum && trustedAssetUrl(checksum.browser_download_url)) {
        elements.checksum.href = checksum.browser_download_url;
        elements.checksum.target = "_blank";
        elements.checksum.rel = "noreferrer";
        elements.checksum.hidden = false;
      } else {
        elements.checksum.hidden = true;
      }
    }

    const releaseDownloads = assets.reduce((total, asset) => total + (Number(asset.download_count) || 0), 0);
    if (elements.downloads) elements.downloads.textContent = formatNumber(releaseDownloads);
    if (elements.status) {
      const packageCount = [hasApk, hasMsi, hasExe].filter(Boolean).length;
      elements.status.textContent = packageCount
        ? "安装包信息直接来自 GitHub 最新 Release。"
        : "此版本未找到可识别的安装包，请打开 Release 页面查看。";
    }
  }

  async function loadPublicRepositoryInfo() {
    const results = await Promise.allSettled([
      loadJson("https://api.github.com/repos/" + repository),
      loadJson("https://api.github.com/repos/" + repository + "/releases/latest"),
    ]);

    const repositoryResult = results[0];
    const releaseResult = results[1];

    if (repositoryResult.status === "fulfilled" && elements.stars) {
      elements.stars.textContent = formatNumber(repositoryResult.value.stargazers_count);
    }

    if (releaseResult.status === "fulfilled") {
      applyRelease(releaseResult.value);
    } else {
      setFallback("暂时无法读取最新附件；请前往 GitHub Releases 查看。");
    }
  }

  loadPublicRepositoryInfo().catch(() => {
    setFallback("暂时无法读取最新附件；请前往 GitHub Releases 查看。");
  });
})();
