/* Page behavior for generated documentation. No dependencies, no network.
   - Screenshot steppers: show one visual state at a time with prev/next.
   Works from file:// so a copied project is fully interactive offline. */
(function () {
  "use strict";

  function initStepper(stepper) {
    var frames = Array.prototype.slice.call(stepper.querySelectorAll(".frame"));
    if (!frames.length) return;
    var prev = stepper.querySelector(".step-prev");
    var next = stepper.querySelector(".step-next");
    var label = stepper.querySelector(".step-label");
    var i = 0;

    function render() {
      frames.forEach(function (f, idx) {
        f.classList.toggle("active", idx === i);
      });
      if (label) label.textContent = "STATE " + (i + 1) + " / " + frames.length;
      if (prev) prev.disabled = i === 0;
      if (next) next.disabled = i === frames.length - 1;
    }
    if (prev) prev.addEventListener("click", function () { if (i > 0) { i--; render(); } });
    if (next) next.addEventListener("click", function () { if (i < frames.length - 1) { i++; render(); } });
    render();
  }

  document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll(".stepper").forEach(initStepper);
    if (window.RetroHighlight) window.RetroHighlight.all();
  });
})();
