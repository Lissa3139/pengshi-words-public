(() => {
  const canvas = document.querySelector("#word-field");
  const stage = canvas?.closest(".hero-art");
  const context = canvas?.getContext("2d");
  if (!canvas || !stage || !context) return;

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const random = seededRandom(0x5eed2026);
  const points = Array.from({ length: 148 }, (_, index) => ({
    x: random(),
    y: random(),
    phase: random() * Math.PI * 2,
    drift: 1.5 + random() * 8,
    mint: index % 6 === 0 || index % 11 === 0,
  }));

  let width = 0;
  let height = 0;
  let pixelRatio = 1;
  let frame = 0;
  let startedAt = performance.now();
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

  function resize() {
    const bounds = stage.getBoundingClientRect();
    width = bounds.width;
    height = bounds.height;
    pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(height * pixelRatio);
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    draw(performance.now());
  }

  function draw(timestamp) {
    if (!width || !height) return;

    context.clearRect(0, 0, width, height);
    const seconds = reducedMotion.matches ? 0 : (timestamp - startedAt) / 1000;
    const rippleAge = timestamp - rippleStartedAt;
    const rippleActive = pointer && !reducedMotion.matches && rippleAge >= 0 && rippleAge < 1200;
    const rippleProgress = rippleActive ? rippleAge / 1200 : 0;
    const rippleRadius = rippleProgress * Math.min(width, height) * 0.36;

    for (const point of points) {
      const swayX = reducedMotion.matches ? 0 : Math.sin(seconds * 0.2 + point.phase) * point.drift;
      const swayY = reducedMotion.matches ? 0 : Math.cos(seconds * 0.16 + point.phase) * point.drift * 0.55;
      const x = point.x * width + swayX;
      const y = point.y * height + swayY;
      let pulse = 0;

      if (rippleActive) {
        const distance = Math.hypot(x - pointer.x, y - pointer.y);
        const waveDistance = Math.abs(distance - rippleRadius);
        pulse = Math.max(0, 1 - waveDistance / 13) * (1 - rippleProgress);
      }

      context.beginPath();
      context.fillStyle = point.mint ? "#63e3cc" : "#78969d";
      context.globalAlpha = point.mint ? 0.86 + pulse * 0.14 : 0.58 + pulse * 0.3;
      context.arc(x, y, (point.mint ? 1.65 : 1.1) + pulse * 1.5, 0, Math.PI * 2);
      context.fill();
    }

    context.globalAlpha = 1;
    if (rippleActive) drawRipple(rippleRadius, rippleProgress);
  }

  function drawRipple(radius, progress) {
    for (let ring = 0; ring < 2; ring += 1) {
      const delay = ring * 0.18;
      const ringProgress = Math.max(0, Math.min(1, (progress - delay) / (1 - delay)));
      if (progress < delay) continue;

      context.beginPath();
      context.strokeStyle = "rgba(99, 227, 204, " + (0.25 * (1 - ringProgress)) + ")";
      context.lineWidth = 1;
      context.arc(pointer.x, pointer.y, radius * (1 - delay), 0, Math.PI * 2);
      context.stroke();
    }
  }

  function animate(timestamp) {
    draw(timestamp);
    frame = requestAnimationFrame(animate);
  }

  function startAnimation() {
    cancelAnimationFrame(frame);
    frame = 0;
    startedAt = performance.now();
    if (reducedMotion.matches || document.hidden) {
      draw(startedAt);
      return;
    }
    frame = requestAnimationFrame(animate);
  }

  stage.addEventListener("pointermove", (event) => {
    if (reducedMotion.matches) return;
    const bounds = stage.getBoundingClientRect();
    pointer = { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
    rippleStartedAt = performance.now();
  });

  stage.addEventListener("pointerleave", () => {
    pointer = null;
  });

  document.addEventListener("visibilitychange", startAnimation);
  if (typeof reducedMotion.addEventListener === "function") {
    reducedMotion.addEventListener("change", startAnimation);
  } else {
    reducedMotion.addListener(startAnimation);
  }

  if ("ResizeObserver" in window) {
    new ResizeObserver(resize).observe(stage);
  } else {
    window.addEventListener("resize", resize, { passive: true });
  }

  resize();
  startAnimation();
})();
