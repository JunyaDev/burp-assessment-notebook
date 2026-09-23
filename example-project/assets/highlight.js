/* Tiny offline syntax highlighter for the notebook's captured source blocks.
   Scans the text content of <code class="lang-XXX"> elements and wraps tokens
   in <span class="tok-*">. No dependencies; safe to run from file://.
   Coverage is deliberately modest (js, css, json, html) — enough to read code,
   not a full parser. Unknown languages are left as plain monospaced text. */
(function () {
  "use strict";

  var JS_KW = new RegExp("\\b(?:var|let|const|function|return|if|else|for|while|do|switch|case|" +
    "break|continue|new|typeof|instanceof|in|of|this|class|extends|super|import|export|from|" +
    "default|try|catch|finally|throw|async|await|yield|null|undefined|true|false|void|delete)\\b");

  var CSS_KW = new RegExp("\\b(?:important|inherit|initial|none|auto|flex|grid|block|inline|" +
    "absolute|relative|fixed|solid|dotted|dashed)\\b");

  function esc(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  // Each rule: sticky regex + token class. First match at the cursor wins.
  function rules(lang) {
    var s = function (re) { return new RegExp(re.source, "y"); };
    var lineComment = s(/\/\/[^\n]*/);
    var blockComment = s(/\/\*[\s\S]*?\*\//);
    var dq = s(/"(?:\\.|[^"\\])*"/);
    var sq = s(/'(?:\\.|[^'\\])*'/);
    var tpl = s(/`(?:\\.|[^`\\])*`/);
    var num = s(/\b\d+(?:\.\d+)?\b/);

    if (lang === "js" || lang === "json") {
      var out = [[blockComment, "com"], [dq, "str"], [sq, "str"], [tpl, "str"], [num, "num"]];
      if (lang === "js") {
        out.splice(0, 0, [lineComment, "com"]);
        out.push([s(new RegExp(JS_KW.source)), "kw"]);
      }
      return out;
    }
    if (lang === "css") {
      return [[blockComment, "com"], [dq, "str"], [sq, "str"],
              [s(/#[0-9a-fA-F]{3,8}\b/), "num"], [num, "num"],
              [s(new RegExp(CSS_KW.source)), "kw"],
              [s(/[.#][-\w]+/), "attr"], [s(/[-a-z]+(?=\s*:)/), "kw"]];
    }
    if (lang === "html") {
      return [[s(/<!--[\s\S]*?-->/), "com"], [dq, "str"], [sq, "str"],
              [s(/<\/?[a-zA-Z][\w:-]*/), "tag"], [s(/[a-zA-Z-]+(?==)/), "attr"],
              [s(/[<>\/=]/), "punc"]];
    }
    return [];
  }

  function highlight(code, lang) {
    var rs = rules(lang);
    if (!rs.length) return esc(code);
    var out = "";
    var i = 0;
    var n = code.length;
    while (i < n) {
      var matched = false;
      for (var r = 0; r < rs.length; r++) {
        var re = rs[r][0];
        re.lastIndex = i;
        var m = re.exec(code);
        if (m && m.index === i && m[0].length > 0) {
          out += '<span class="tok-' + rs[r][1] + '">' + esc(m[0]) + "</span>";
          i += m[0].length;
          matched = true;
          break;
        }
      }
      if (!matched) {
        out += esc(code[i]);
        i++;
      }
    }
    return out;
  }

  function langOf(el) {
    var m = /(?:^|\s)lang-([\w]+)/.exec(el.className || "");
    return m ? m[1] : "text";
  }

  window.RetroHighlight = {
    all: function () {
      document.querySelectorAll('code[class*="lang-"]').forEach(function (el) {
        if (el.dataset.hl === "1") return;
        el.innerHTML = highlight(el.textContent, langOf(el));
        el.dataset.hl = "1";
      });
    }
  };
})();
