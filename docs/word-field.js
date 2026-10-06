(() => {
  const canvas = document.querySelector("#word-field");
  const context = canvas?.getContext("2d", { alpha: true });
  if (!canvas || !context) return;

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const revealGroupCount = 24;
  const revealSeconds = 2.8;
  const groups = Array.from({ length: revealGroupCount }, (_, index) => ({
    startsAt: 0.8 + index * 0.82,
    common: [],
    mint: [],
  }));
  let points = [];
  let logoMask = [];
  let width = 0;
  let height = 0;
  let pixelRatio = 1;
  let frame = 0;
  let lastFrameAt = 0;
  let startedAt = performance.now();
  let targetProgress = 0;
  let scrollProgress = 0;
  let pointer = null;
  let rippleStartedAt = 0;

  function seededRandom(seed) {
    let value = seed >>> 0;
    return () => {
      value += 0x6d2b79f5;
      let result = value;
      result = Math.imul(result ^ (result >>> 15), result | 1);
      result ^= result + Math.imul(result ^ (result >>> 7), result | 61);
      return ((result ^ (result >>> 14)) >>> 0) / 4294967296;
    };
  }

  function clamp(value, min = 0, max = 1) {
    return Math.max(min, Math.min(max, value));
  }

  function smoothstep(value) {
    const progress = clamp(value);
    return progress * progress * (3 - 2 * progress);
  }

  function mixColor(from, to, amount) {
    const progress = clamp(amount);
    const a = [1, 3, 5].map((index) => Number.parseInt(from.slice(index, index + 2), 16));
    const b = [1, 3, 5].map((index) => Number.parseInt(to.slice(index, index + 2), 16));
    return "rgb(" + a.map((channel, index) => Math.round(channel + (b[index] - channel) * progress)).join(",") + ")";
  }

  function buildLogoMask(image) {
    const sampleSize = 144;
    const sampleCanvas = document.createElement("canvas");
    sampleCanvas.width = sampleSize;
    sampleCanvas.height = sampleSize;
    const sampleContext = sampleCanvas.getContext("2d", { willReadFrequently: true });
    if (!sampleContext) return;

    sampleContext.drawImage(image, 0, 0, sampleSize, sampleSize);
    const pixels = sampleContext.getImageData(0, 0, sampleSize, sampleSize).data;
    logoMask = [];
    for (let y = 0; y < sampleSize; y += 1) {
      for (let x = 0; x < sampleSize; x += 1) {
        if (pixels[(y * sampleSize + x) * 4 + 3] > 48) {
          logoMask.push({ x: (x + 0.5) / sampleSize, y: (y + 0.5) / sampleSize });
        }
      }
    }
    const random = seededRandom(0x10a0 + points.length);
    for (const point of points) {
      const target = logoMask[Math.floor(random() * logoMask.length)];
      if (target) {
        point.targetX = target.x;
        point.targetY = target.y;
      }
    }
    draw(performance.now());
  }

  const logo = new Image();
  logo.addEventListener("load", () => buildLogoMask(logo), { once: true });
  logo.src = document.querySelector(".hero-logo")?.currentSrc || "images/site/pengshi_logo.png";

  function createPoints() {
    for (const group of groups) {
      group.common.length = 0;
      group.mint.length = 0;
    }

    const coarsePointer = window.matchMedia("(pointer: coarse)").matches;
    const minimum = coarsePointer ? 1000 : 1500;
    const count = Math.min(7000, Math.max(minimum, Math.round((width * height) / 230)));
    const random = seededRandom(0x5eed2026 + count);
    points = Array.from({ length: count }, (_, index) => {
      const group = groups[Math.floor(random() * groups.length)];
      const target = logoMask.length ? logoMask[Math.floor(random() * logoMask.length)] : { x: random(), y: random() };
      const point = {
        x: random(),
        y: random(),
        targetX: target.x,
        targetY: target.y,
        phase: random() * Math.PI * 2,
        drift: index % 7 === 0 ? 2.5 + random() * 5 : 0,
        radius: 0.48 + random() * 0.82,
        large: index % 53 === 0,
      };
      (random() < 0.11 ? group.mint : group.common).push(point);
      return point;
    });
    return points;
  }

  function updateScrollProgress() {
    const maximum = document.documentElement.scrollHeight - window.innerHeight;
    targetProgress = maximum > 0 ? clamp(window.scrollY / maximum) : 0;
  }

  function resize() {
    const bounds = canvas.getBoundingClientRect();
    width = bounds.width || window.innerWidth;
    height = bounds.height || window.innerHeight;
    pixelRatio = Math.min(window.devicePixelRatio || 1, 1.5);
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(height * pixelRatio);
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    createPoints();
    updateScrollProgress();
    draw(performance.now());
  }

  function drawGroup(group, pointsInGroup, progress, seconds, isMint, logoSize, logoCenterY) {
    if (!pointsInGroup.length) return;

    const reveal = reducedMotion.matches ? 1 : smoothstep((seconds - group.startsAt) / revealSeconds);
    const alpha = (isMint ? 0.12 : 0.075) + reveal * (isMint ? 0.78 : 0.7);
    const darkColor = isMint ? "#17463f" : "#526777";
    const brightColor = isMint ? "#72f0d9" : "#c0d7df";
    const color = mixColor(darkColor, brightColor, reveal);
    context.beginPath();
    for (const point of pointsInGroup) {
      const swayX = point.drift ? Math.sin(seconds * 0.45 + point.phase) * point.drift : 0;
      const swayY = point.drift ? Math.cos(seconds * 0.34 + point.phase) * point.drift * 0.62 : 0;
      const scatteredX = point.x * width + swayX;
      const scatteredY = point.y * height + swayY;
      const targetX = width * 0.5 + (point.targetX - 0.5) * logoSize;
      const targetY = logoCenterY + (point.targetY - 0.5) * logoSize;
      const x = scatteredX + (targetX - scatteredX) * progress;
      const y = scatteredY + (targetY - scatteredY) * progress;
      const radius = point.radius * (point.large ? 1.65 : 1);
      context.moveTo(x + radius, y);
      context.arc(x, y, radius, 0, Math.PI * 2);
    }
    context.fillStyle = color;
    context.globalAlpha = alpha * (1 - progress * 0.58);
    context.fill();
  }

  function draw(timestamp) {
    if (!width || !height) return;

    const seconds = reducedMotion.matches ? 24 : Math.max(0, (timestamp - startedAt) / 1000);
    const target = reducedMotion.matches ? 0 : targetProgress;
    scrollProgress += (target - scrollProgress) * (reducedMotion.matches ? 1 : 0.075);
    if (Math.abs(target - scrollProgress) < 0.001) scrollProgress = target;
    const progress = smoothstep(scrollProgress);
    const narrowViewport = width < 620;
    const logoSize = Math.min(Math.min(width, height) * (narrowViewport ? 0.42 : 0.34), narrowViewport ? 175 : 270);
    const logoCenterY = height * (narrowViewport ? 0.14 : 0.26);

    context.clearRect(0, 0, width, height);
    for (const group of groups) {
      drawGroup(group, group.common, progress, seconds, false, logoSize, logoCenterY);
      drawGroup(group, group.mint, progress, seconds, true, logoSize, logoCenterY);
    }
    context.globalAlpha = 1;

    const rippleAge = timestamp - rippleStartedAt;
    if (pointer && !reducedMotion.matches && rippleAge >= 0 && rippleAge < 1000) {
      const rippleProgress = rippleAge / 1000;
      context.beginPath();
      context.strokeStyle = "rgba(108, 230, 207, " + 0.26 * (1 - rippleProgress) + ")";
      context.lineWidth = 1;
      context.arc(pointer.x, pointer.y, rippleProgress * Math.min(width, height) * 0.28, 0, Math.PI * 2);
      context.stroke();
    }
  }

  function animate(timestamp) {
    if (timestamp - lastFrameAt >= 32) {
      draw(timestamp);
      lastFrameAt = timestamp;
    }
    frame = requestAnimationFrame(animate);
  }

  function syncAnimation() {
    cancelAnimationFrame(frame);
    frame = 0;
    if (document.hidden) return;
    if (reducedMotion.matches) {
      scrollProgress = 0;
      draw(performance.now());
      return;
    }
    lastFrameAt = 0;
    frame = requestAnimationFrame(animate);
  }

  window.addEventListener("pointermove", (event) => {
    if (reducedMotion.matches) return;
    pointer = { x: event.clientX, y: event.clientY };
    rippleStartedAt = performance.now();
  }, { passive: true });
  window.addEventListener("pointerleave", () => { pointer = null; });
  window.addEventListener("scroll", updateScrollProgress, { passive: true });
  window.addEventListener("resize", resize, { passive: true });
  document.addEventListener("visibilitychange", syncAnimation);
  if (typeof reducedMotion.addEventListener === "function") {
    reducedMotion.addEventListener("change", syncAnimation);
  } else {
    reducedMotion.addListener(syncAnimation);
  }

  resize();
  syncAnimation();
})();
