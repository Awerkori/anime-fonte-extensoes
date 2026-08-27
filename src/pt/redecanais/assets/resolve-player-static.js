(function() {
  var runToken = window.__rcStaticResolverRunToken || 0;
  if (window.__rcStaticResolverActiveToken === runToken) return true;
  window.__rcStaticResolverActiveToken = runToken;

  var markerNames = [
    "VIDEO_URL_POST_BASE64_VkNfU0VfRlVERVVfT1RBUklPX1ZBX1BST0NVUkFSX0VNX09VVFJPX0xVR0FS",
    "VIDEO_URL_POST_BASE64_SEVfU0VfRlVERVVfT1RBUklPX1ZBX1BST0NVUkFSX0VNX09VVFJPX0xVR0FS",
    "VIDEO_URL_POST_BASE64_U0VfRlVERVVfT1RBUklPX1ZBX1BST0NVUkFSX0VNX09VVFJPX0xVR0FS",
    "VIDEO_URL_POST_BASE64_S0tfU0VfRlVERVVfT1RBUklPX1ZBX1BST0NVUkFSX0VNX09VVFJPX0xVR0FS"
  ];
  var triedApis = {};
  var preloadAttempts = 0;
  var preloadPending = false;
  var staticScanned = false;
  var ticks = 0;

  function absoluteHttp(value) {
    if (typeof value !== "string" || !value.trim()) return "";
    try {
      var url = new URL(value.trim(), location.href);
      return url.protocol === "http:" || url.protocol === "https:" ? url.href : "";
    } catch (error) {
      return "";
    }
  }

  function pass(value) {
    var url = absoluteHttp(value);
    if (!url || window.__rcStaticResolverDone) return false;
    window.__rcStaticResolverDone = true;
    window.__rcStaticResolverActiveToken = 0;
    window.PlayerApiSniffer.passResult(runToken, location.href, url);
    return true;
  }

  function readMarkers() {
    for (var i = 0; i < markerNames.length; i++) {
      try {
        if (pass(window[markerNames[i]])) return true;
      } catch (error) {
      }
    }
    return false;
  }

  function unescapePart(value) {
    return value
      .replace(/\\x([0-9a-fA-F]{2})/g, function(_, hex) { return String.fromCharCode(parseInt(hex, 16)); })
      .replace(/\\u([0-9a-fA-F]{4})/g, function(_, hex) { return String.fromCharCode(parseInt(hex, 16)); })
      .replace(/\\(["'\\/])/g, function(_, character) { return character; });
  }

  function decodedLayers(input) {
    var queue = [input || ""];
    var output = [];
    var seen = {};
    while (queue.length && output.length < 40) {
      var value = queue.shift();
      if (!value || value.length > 900000 || seen[value]) continue;
      seen[value] = true;
      output.push(value);

      var unescaped = unescapePart(value);
      if (unescaped !== value) queue.push(unescaped);

      var numberMatch;
      var numberRegex = /\[((?:\s*\d{1,3}\s*,){5,}\s*\d{1,3}\s*)\]/g;
      while ((numberMatch = numberRegex.exec(value)) && output.length + queue.length < 40) {
        try {
          var numbers = numberMatch[1].split(",");
          var decoded = "";
          for (var i = 0; i < numbers.length; i++) decoded += String.fromCharCode(parseInt(numbers[i], 10));
          queue.push(decoded);
        } catch (error) {
        }
      }

      var base64Match;
      var base64Regex = /["']([A-Za-z0-9+/]{80,}={0,2})["']/g;
      while ((base64Match = base64Regex.exec(value)) && output.length + queue.length < 40) {
        try {
          queue.push(atob(base64Match[1]));
        } catch (error) {
        }
      }
    }
    return output;
  }

  function extractVideoUrl(decoded) {
    for (var i = 0; i < markerNames.length; i++) {
      var expression = new RegExp("(?:const|let|var)\\s+" + markerNames[i] + "\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')");
      var match = expression.exec(decoded);
      if (!match) continue;
      try {
        return JSON.parse(match[1]);
      } catch (error) {
        return unescapePart(match[1].slice(1, -1));
      }
    }
    return "";
  }

  function decodeApiBody(body) {
    try {
      var stage1 = atob((body || "").trim());
      var match = /var\s+c\s*=\s*\[([0-9,\s]+)\]/.exec(stage1);
      if (!match) return "";
      var numbers = match[1].split(",");
      var decoded = "";
      for (var i = 0; i < numbers.length; i++) decoded += String.fromCharCode(parseInt(numbers[i], 10));
      return extractVideoUrl(decoded);
    } catch (error) {
      return "";
    }
  }

  function usableApi(url) {
    var lower = (url || "").toLowerCase();
    return lower.indexOf(".api") >= 0 &&
      lower.indexOf("/player3/dt.api") < 0 &&
      lower.indexOf("videojs.thumbnails.api") < 0;
  }

  function fetchApi(url) {
    if (!usableApi(url) || triedApis[url]) return;
    triedApis[url] = true;
    fetch(url, {
      method: "GET",
      credentials: "include",
      cache: "no-store",
      referrer: location.href,
      headers: { "Accept": "*/*" }
    }).then(function(response) {
      return response.text();
    }).then(function(body) {
      pass(decodeApiBody(body));
    }).catch(function() {
    });
  }

  function scanStaticScripts() {
    var scripts = document.scripts || [];
    for (var i = 0; i < scripts.length; i++) {
      if (scripts[i].src) continue;
      var layers = decodedLayers(scripts[i].text || scripts[i].textContent || "");
      for (var j = 0; j < layers.length; j++) {
        var apiMatch;
        var apiRegex = /(?:https?:\/\/[^"'\s<>]+|\/?player3\/[^"'\s<>]+\.api(?:\?[^"'\s<>]*)?)/gi;
        while ((apiMatch = apiRegex.exec(layers[j]))) {
          try {
            fetchApi(new URL(apiMatch[0], location.href).href);
          } catch (error) {
          }
        }
      }
    }
  }

  function scanRuntimeApis() {
    try {
      var resources = performance.getEntriesByType("resource");
      var candidates = [];
      for (var i = 0; i < resources.length; i++) {
        var url = resources[i].name || "";
        if (usableApi(url)) candidates.push(url);
      }
      candidates.sort(function(left, right) {
        return (right.indexOf("jquery.videojs") >= 0 ? 1 : 0) - (left.indexOf("jquery.videojs") >= 0 ? 1 : 0);
      });
      for (var j = 0; j < candidates.length; j++) fetchApi(candidates[j]);
    } catch (error) {
    }
  }

  function runPreloader() {
    if (typeof window.rcPreloadPlayer !== "function" || preloadAttempts >= 2 || preloadPending) return false;
    preloadAttempts++;
    preloadPending = true;
    Promise.resolve(window.rcPreloadPlayer(Date.now())).then(function() {
      preloadPending = false;
      if (!readMarkers()) {
        scanRuntimeApis();
        if (preloadAttempts < 2) setTimeout(runPreloader, 350);
      }
    }).catch(function() {
      preloadPending = false;
      if (preloadAttempts < 2) setTimeout(runPreloader, 350);
    });
    return true;
  }

  function tick() {
    if (window.__rcStaticResolverDone || window.__rcStaticResolverActiveToken !== runToken) return;
    if (readMarkers()) return;
    var preloaderAvailable = typeof window.rcPreloadPlayer === "function";
    runPreloader();
    if (!preloaderAvailable && !staticScanned) {
      staticScanned = true;
      scanStaticScripts();
    }
    scanRuntimeApis();
    ticks++;
    if (ticks < 60) setTimeout(tick, 100);
  }

  tick();
  return true;
})();
