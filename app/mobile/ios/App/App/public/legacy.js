/* 老系统兼容探测：只做一件事 —— 老 WebKit 的 flex 不支持 gap，
   探测到就在 <html> 上加 no-flex-gap，让 legacy.css 的间距规则接手。 */
(function () {
  try {
    var box = document.createElement('div');
    box.style.cssText = 'display:flex;gap:10px;position:absolute;left:-9999px;top:0;width:100px;visibility:hidden';
    box.innerHTML = '<i style="flex:1 1 auto"></i><i style="flex:1 1 auto"></i>';
    document.documentElement.appendChild(box);
    var kids = box.children;
    var ok = kids.length === 2 &&
      (kids[1].getBoundingClientRect().left - kids[0].getBoundingClientRect().right) >= 9;
    document.documentElement.removeChild(box);
    if (!ok) {
      document.documentElement.className += ' no-flex-gap';
      window.__noFlexGap = true;
    }
  } catch (e) {
    document.documentElement.className += ' no-flex-gap';
  }
})();
