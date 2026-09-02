(function() {
  function captureHtml() {
    if (/\/musicvideo\.php$/i.test(location.pathname)) {
      var player = document.querySelector("#video-wrapper iframe[name='Player'][src], #video-wrapper iframe[src*='/player3/server.php']");
      return "<!doctype html><html><body>" +
        (player ? player.outerHTML : "") +
        "</body></html>";
    }

    var list = document.querySelector("ul.pm-ul-browse-videos");
    if (list && list.querySelector("li")) {
      var pagination = document.querySelector("ul.pagination");
      return "<!doctype html><html><body>" +
        list.outerHTML +
        (pagination ? pagination.outerHTML : "") +
        "</body></html>";
    }

    return document.documentElement ? document.documentElement.outerHTML : "";
  }

  var contentType = document.contentType || "text/html";
  var finished = false;

  function pass(statusCode, detectedContentType) {
    if (finished) return;
    if (!searchMapsDone) {
      pendingPass = [statusCode, detectedContentType];
      return;
    }

    finished = true;
    window.HtmlProxy.passHtml(
      statusCode,
      detectedContentType || contentType,
      captureHtml()
    );
  }

  var pendingPass = null;
  var waitSearchMaps = /\/search\.php$/i.test(location.pathname);
  var searchMapsDone = !waitSearchMaps;
  var imagesDone = false;
  var pageDone = false;

  function finishSearchMaps() {
    searchMapsDone = true;
    if (pendingPass) pass(pendingPass[0], pendingPass[1]);
    finishPageIfReady();
  }

  function finishImages() {
    imagesDone = true;
    finishPageIfReady();
  }

  function finishPageIfReady() {
    if (pageDone) return;
    if (searchMapsDone && imagesDone) {
      pageDone = true;
      window.HtmlProxy.passDone();
    }
  }

  function warmSearchMaps() {
    var files = ["final_mapa.txt", "final_mapafilmes.txt"];
    var remaining = files.length;

    function done() {
      remaining--;
      if (remaining === 0) finishSearchMaps();
    }

    files.forEach(function(file) {
      fetch("/" + file, {
        method: "GET",
        credentials: "include",
        headers: { "Accept": "text/plain,*/*;q=0.8" }
      }).then(function(response) {
        return response.ok ? response.text() : "";
      }).then(function(text) {
        if (text) window.HtmlProxy.passSearchMap(file, text);
        done();
      }, done);
    });
  }

  function passImageBytes(url, buffer) {
    var bytes = new Uint8Array(buffer);
    var chunkSize = 0x6000;

    for (var i = 0; i < bytes.length; i += chunkSize) {
      var binary = "";
      var end = Math.min(i + chunkSize, bytes.length);
      for (var j = i; j < end; j++) {
        binary += String.fromCharCode(bytes[j]);
      }
      window.HtmlProxy.passImageChunk(url, btoa(binary));
    }
  }

  function imageUrl(value) {
    try {
      var url = new URL(value, document.baseURI);
      if (!/^redecanais\./i.test(url.host)) return "";

      url.protocol = location.protocol;
      url.host = location.host;
      return url.href;
    } catch (error) {
      return "";
    }
  }

  function captureImages() {
    var seen = Object.create(null);
    var urls = [];

    Array.prototype.slice.call(
      document.querySelectorAll("ul.pm-ul-browse-videos > li .pm-video-thumb img, ul.pm-ul-browse-videos > li img")
    ).forEach(function(image) {
      var value = image.getAttribute("data-echo") || image.currentSrc || image.src;
      if (!value) return;

      var url = imageUrl(value);
      if (!url) return;
      if (seen[url]) return;
      if (!/\.(jpe?g|png|webp|gif)(?:[?#].*)?$/i.test(url)) return;

      seen[url] = true;
      urls.push(url);
    });

    urls = urls.slice(0, Math.min(urls.length, 48));
    var maxConcurrent = 8;

    urls.forEach(function(url) {
      window.HtmlProxy.expectImage(url);
    });

    pass(200, contentType);
    if (urls.length === 0) {
      finishImages();
      return;
    }

    var index = 0;
    var active = 0;
    var doneCount = 0;

    function finishOne(next) {
      active--;
      doneCount++;
      if (doneCount === urls.length) {
        finishImages();
        return;
      }
      if (next) loadNext();
    }

    function loadOne(url) {
      active++;
      fetch(url, {
        method: "GET",
        credentials: "include",
        headers: { "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8" }
      }).then(function(response) {
        if (!response.ok) throw new Error(String(response.status));

        var detectedContentType = (response.headers.get("content-type") || "image/jpeg").split(";")[0] || "image/jpeg";
        window.HtmlProxy.startImage(url, detectedContentType);
        return response.arrayBuffer();
      }).then(function(buffer) {
        passImageBytes(url, buffer);
        window.HtmlProxy.finishImage(url);
      }).catch(function() {
        window.HtmlProxy.passImageError(url);
      }).then(function() {
        finishOne(true);
      });
    }

    function loadNext() {
      while (active < maxConcurrent && index < urls.length) {
        loadOne(urls[index++]);
      }
      if (active === 0 && index >= urls.length) finishImages();
    }

    loadNext();
  }

  if (waitSearchMaps) {
    warmSearchMaps();
  }
  captureImages();
})();
