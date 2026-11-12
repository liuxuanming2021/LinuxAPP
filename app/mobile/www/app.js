/* LinuxAPP 1.3.1 —— 我的 / 聊天 / 队伍 / 头衔 / 命令 / 更多 */
(function () {
  'use strict';

  var DEFAULT_SERVER = 'http://aam.rainplay.cn:49282';

  var LS = {
    server: 'nalxer.server',
    token: 'nalxer.token',
    name: 'nalxer.name',
    admin: 'nalxer.admin',
    role: 'nalxer.role',
    expire: 'nalxer.expire',
    theme: 'nalxer.theme',
    skin: 'nalxer.skin',
    accent: 'nalxer.accent',
    navfloat: 'nalxer.navfloat',
    suon: 'nalxer.suon',
    notify: 'nalxer.notify',
    noticeOut: 'nalxer.notice.out',
    servers: 'nalxer.servers',
    serverIdx: 'nalxer.server.idx',
    multi: 'nalxer.multi'
  };

  var PAGE_TITLE = {
    me: ['我的数据', ''],
    chat: ['社交', '公屏 / 队伍频道 / 队伍'],
    title: ['头衔', 'LinuxTitle'],
    admin: ['服务器管理', '玩家 / 举报 / 惩罚 / 假人 / 命令'],
    more: ['更多', '设置 / 接口地址 / 关于'],
    pdetail: ['玩家详情', ''],
    servers: ['软件链接配置', '多服务器（Beta）']
  };

  var state = {
    server: '',
    token: '',
    name: '',
    admin: false,
    role: 'player',
    canManage: false,
    moderatorOnly: false,
    page: 'me',
    lastAdminTab: 'player',
    me: null,
    chatLast: 0,
    chatMsgs: [],
    chatChannel: 'world',
    chatScrollPending: false,
    chatTeam: '',
    myTeams: [],
    chatTimer: 0,
    adminTab: 'player',
    reportFilter: 'all',
    needVerify: false,
    cmdCatalog: null,
    cmdGroup: '',
    timer: 0
  };

  // 元素可能因为改版被删掉，取不到就当没这回事，别让整个 boot 挂掉
  function show(id, visible) {
    var el = $(id);
    if (el) el.classList.toggle('hidden', !visible);
  }

  function $(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /** 把 §6[元老] §r 这类色码去掉，只留文字 */
  function plain(s) {
    return String(s == null ? '' : s).replace(/[\u00a7&][0-9a-fk-orx]/gi, '');
  }

  function normServer(v) {
    var s = String(v == null ? '' : v).trim();
    if (!s) return DEFAULT_SERVER;
    if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
    return s.replace(/\/+$/, '');
  }

  // ── 多服务器（Beta） ──────────────────────────────────
  function serversLoad() {
    try {
      var a = JSON.parse(localStorage.getItem(LS.servers) || '[]');
      if (!Array.isArray(a)) a = [];
      while (a.length < 5) a.push({ name: '', url: '', token: '', player: '', role: '' });
      return a.slice(0, 5);
    } catch (e) {
      var z = [];
      for (var i = 0; i < 5; i++) z.push({ name: '', url: '', token: '', player: '', role: '' });
      return z;
    }
  }

  function serversSave(list) {
    localStorage.setItem(LS.servers, JSON.stringify(list.slice(0, 5)));
  }

  function multiOn() { return localStorage.getItem(LS.multi) === '1'; }
  function setMultiOn(v) { localStorage.setItem(LS.multi, v ? '1' : '0'); }
  function activeIdx() {
    var i = Number(localStorage.getItem(LS.serverIdx) || 0);
    return (isFinite(i) && i >= 0 && i < 5) ? i : 0;
  }

  /** 把当前这台服务器的登录状态写回它的槽位。 */
  function slotSync() {
    var list = serversLoad();
    var i = activeIdx();
    var one = list[i] || {};
    one.url = state.server;
    one.token = state.token || '';
    one.player = state.name || '';
    one.role = state.role || 'player';
    if (!one.name) one.name = String(state.server || '').replace(/^https?:\/\//, '');
    list[i] = one;
    serversSave(list);
    return list;
  }

  function validSlots() {
    return serversLoad().map(function (s, i) { return { s: s, i: i }; })
      .filter(function (x) { return x.s && x.s.url; });
  }

  function switchServer(i) {
    slotSync();
    var list = serversLoad();
    var target = list[i];
    if (!target || !target.url) { toast('这台服务器还没配置地址'); return; }
    localStorage.setItem(LS.serverIdx, String(i));
    state.server = normServer(target.url);
    state.token = target.token || '';
    state.name = target.player || '';
    state.role = target.role || 'player';
    state.admin = state.role === 'admin' || state.role === 'owner' || state.role === 'su';
    // 每台服务器的数据互不串：清干净再拉
    state.chatMsgs = [];
    state.chatLast = 0;
    state.chatRenderedMax = 0;
    state.notifiedMax = 0;
    state.myTeams = [];
    state.reports = [];
    persist();
    renderServersUi();
    if (!state.token) {
      showLogin();
      toast('这台服务器需要重新登录');
      return;
    }
    switchPage('me');
    loadMe();
    if (typeof loadStatus === 'function') loadStatus();
    loadChat(true);
    loadTeam();
    toast('已切到「' + (target.name || target.url) + '」');
  }

  function toast(msg) {
    var el = $('toast');
    el.textContent = msg;
    el.classList.remove('hidden');
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.classList.add('hidden'); }, 2200);
  }

  function fatal(prefix, detail) {
    var el = $('fatal');
    el.textContent = prefix + (detail ? '\n' + detail : '');
    el.classList.remove('hidden');
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.classList.add('hidden'); }, 8000);
  }

  // ── 主题 ─────────────────────────────────────────────

  // 顶部状态栏：浅色主题要「深色字」，深色主题要「浅色字」，否则白字压在浅色顶栏上看不清
  function syncStatusBar(dark) {
    var sb = (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.StatusBar) || null;
    if (!sb) return;
    try {
      if (sb.setStyle) sb.setStyle({ style: dark ? 'DARK' : 'LIGHT' }).catch(function () {});
      if (sb.setBackgroundColor) {
        // iOS 不支持改背景色（会 reject），这行只对安卓生效
        sb.setBackgroundColor({ color: dark ? '#0b0f14' : '#f4f6fa' }).catch(function () {});
      }
    } catch (e) {}
  }

  function applyTheme(dark) {
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#0b0f14' : '#f4f6fa');
    var sw = $('sw-theme');
    if (sw) sw.setAttribute('aria-checked', dark ? 'true' : 'false');
    syncStatusBar(dark);
    if (typeof applySkin === 'function' && localStorage.getItem(LS.skin) !== null) applySkin();
  }

  /** 强调色：浅色/深色各一套，默认天蓝色（跟原来一样）。 */
  var ACCENTS = {
    sky:     { name: '天蓝色', light: ['#1f7ae0', 'rgba(31,122,224,0.10)'], dark: ['#4cc2ff', 'rgba(76,194,255,0.16)'] },
    pink:    { name: '淡粉色', light: ['#e0568a', 'rgba(224,86,138,0.12)'], dark: ['#ff8fb8', 'rgba(255,143,184,0.18)'] },
    green:   { name: '淡绿色', light: ['#1f9d63', 'rgba(31,157,99,0.12)'], dark: ['#4bd99a', 'rgba(75,217,154,0.18)'] },
    antique: { name: '古董白', light: ['#a9793f', 'rgba(169,121,63,0.14)'], dark: ['#e3bc87', 'rgba(227,188,135,0.18)'] },
    black:   { name: '黑色', light: ['#1b1f27', 'rgba(27,31,39,0.10)'], dark: ['#e8edf4', 'rgba(232,237,244,0.14)'] },
    gray:    { name: '灰色', light: ['#6b7280', 'rgba(107,114,128,0.14)'], dark: ['#aab3c0', 'rgba(170,179,192,0.18)'] }
  };
  var SKINS = { 'default': '默认', miuix: 'Miuix', ios: 'iOS' };

  function currentSkin() { return localStorage.getItem(LS.skin) || 'default'; }

  /** 悬浮导航栏：默认跟着风格走（iOS 开），用户在主题里手动改过就用他选的。 */
  function navFloating() {
    var saved = localStorage.getItem(LS.navfloat);
    if (saved === '1') return true;
    if (saved === '0') return false;
    return currentSkin() === 'ios';
  }

  function setNavFloating(on) {
    localStorage.setItem(LS.navfloat, on ? '1' : '0');
    applySkin();
    toast(on ? '导航栏悬浮' : '导航栏贴底');
  }
  function currentAccent() {
    var a = localStorage.getItem(LS.accent) || 'sky';
    return ACCENTS[a] ? a : 'sky';
  }

  /** 把风格 + 颜色刷到界面上（深色/浅色各用各的强调色）。 */
  function applySkin() {
    var skin = currentSkin();
    var key = currentAccent();
    var dark = document.documentElement.getAttribute('data-theme') === 'dark';
    document.body.setAttribute('data-skin', skin);
    var float = navFloating();
    document.body.setAttribute('data-nav', float ? 'float' : 'dock');
    var sw = $('sw-navfloat');
    if (sw) sw.setAttribute('aria-checked', float ? 'true' : 'false');
    var pair = dark ? ACCENTS[key].dark : ACCENTS[key].light;
    document.documentElement.style.setProperty('--accent', pair[0]);
    document.documentElement.style.setProperty('--accent-soft', pair[1]);
    renderThemeUi();
  }

  function renderThemeUi() {
    var skin = currentSkin();
    var key = currentAccent();
    var hint = $('set-theme-hint');
    if (hint) hint.textContent = (SKINS[skin] || '默认') + ' · ' + ACCENTS[key].name;
    Array.prototype.forEach.call(document.querySelectorAll('#theme-styles .seg-btn'), function (b) {
      b.classList.toggle('active', b.getAttribute('data-theme-style') === skin);
    });
    Array.prototype.forEach.call(document.querySelectorAll('#theme-swatches .swatch'), function (b) {
      b.classList.toggle('active', b.getAttribute('data-accent') === key);
    });
  }

  function openTheme() {
    if (typeof syncSegs === "function") setTimeout(function () { syncSegs(true); }, 30);

    var m = $('theme-modal');
    if (!m) return;
    m.classList.remove('hidden');
    renderThemeUi();
  }

  function closeTheme() {
    var m = $('theme-modal');
    if (m) m.classList.add('hidden');
  }

  function setSkin(skin) {
    if (!SKINS[skin]) skin = 'default';
    localStorage.setItem(LS.skin, skin);
    applySkin();
    toast('主题风格：' + SKINS[skin]);
  }

  function setAccent(key) {
    if (!ACCENTS[key]) key = 'sky';
    localStorage.setItem(LS.accent, key);
    applySkin();
    toast('颜色：' + ACCENTS[key].name);
  }

  function initTheme() {
    renderNotifySwitch();
    var saved = localStorage.getItem(LS.theme);
    applyTheme(saved === 'dark');
    applySkin();
  }

  function toggleTheme() {
    var dark = document.documentElement.getAttribute('data-theme') !== 'dark';
    applyTheme(dark);
    localStorage.setItem(LS.theme, dark ? 'dark' : 'light');
  }

  // ── 请求 ─────────────────────────────────────────────

  function api(path, opts) {
    opts = opts || {};
    var url = state.server + path;
    var headers = { 'Accept': 'application/json' };
    if (opts.body != null) headers['Content-Type'] = 'application/json';
    if (state.token) headers['Authorization'] = 'Bearer ' + state.token;

    var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = ctrl ? setTimeout(function () { ctrl.abort(); }, 10000) : 0;

    return fetch(url, {
      method: opts.method || (opts.body != null ? 'POST' : 'GET'),
      headers: headers,
      body: opts.body != null ? JSON.stringify(opts.body) : undefined,
      signal: ctrl ? ctrl.signal : undefined
    }).then(function (r) {
      if (timer) clearTimeout(timer);
      return r.text().then(function (txt) {
        var data = null;
        try { data = txt ? JSON.parse(txt) : {}; } catch (e) { data = { ok: false, message: '服务器返回的不是 JSON' }; }
        if (r.status === 401) {
          if (data && data.message) { state.kicked = data.message; }
          else if (data && data.code === 'session_kicked') { state.kicked = '这个账号已在另一台设备登录'; }
          if (!opts.quiet) { logout(true); }
          var e401 = new Error((data && data.message) || '登录已过期，请重新登录');
          e401.status = 401;
          throw e401;
        }
        return { status: r.status, data: data };
      });
    }).catch(function (err) {
      if (timer) clearTimeout(timer);
      if (err && err.name === 'AbortError') {
        throw new Error('连接超时：' + url + '（10 秒没响应，多半是端口没放行或被防火墙挡了）');
      }
      // 服务器已经说清楚原因了（密码不对 / 要验证 / 没权限），原样抛给界面
      if (err && (err.status || err.needVerify)) throw err;
      if (err && err.message && err.message.indexOf('登录已过期') >= 0) throw err;
      if (err && err.message && err.message.indexOf('连接超时') >= 0) throw err;
      throw new Error('连不上 ' + url + ' —— 看这几点：服务器开着吗 / 云服务器安全组放行这个端口了吗 / plugins 里有没有 LinuxAPP');
    });
  }

  // ── 登录 / 退出 ──────────────────────────────────────

  function persist() {
    localStorage.setItem(LS.server, state.server);
    localStorage.setItem(LS.token, state.token);
    localStorage.setItem(LS.name, state.name);
    localStorage.setItem(LS.admin, state.admin ? '1' : '0');
    localStorage.setItem(LS.role, state.role || 'player');
  }

  /** 登录页的「测试连接」：只打 /api/health，把原始错误原样展示。 */
  function diagServer() {
    var NL = String.fromCharCode(10);
    var box = $('diag-box');
    var btn = $('btn-diag');
    var url = normServer($('f-server').value);
    $('f-server').value = url;
    if (!box) return;
    box.classList.remove('hidden');
    box.textContent = '正在连接 ' + url + ' …';
    if (btn) { btn.disabled = true; btn.textContent = '测试中…'; }
    var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = ctrl ? setTimeout(function () { ctrl.abort(); }, 8000) : 0;
    fetch(url + '/api/health', { headers: { 'Accept': 'application/json' }, signal: ctrl ? ctrl.signal : undefined })
      .then(function (r) {
        if (timer) clearTimeout(timer);
        return r.text().then(function (t) { return { code: r.status, body: t }; });
      })
      .then(function (res) {
        var v = '';
        try { v = JSON.parse(res.body).version || ''; } catch (e) {}
        box.textContent = '连接成功：HTTP ' + res.code + (v ? '，服务端版本 ' + v : '') + NL + '地址 ' + url;
      })
      .catch(function (e) {
        if (timer) clearTimeout(timer);
        var msg = (e && e.message) ? e.message : String(e);
        var tip = '';
        if (/App Transport Security|ATS/i.test(msg)) {
          tip = '这是 iOS 的 ATS 拦了 http：重新签名时 Info.plist 被改过（全能签这类工具会重写），重签时不要勾「修改 Info.plist」，或换回我们提供的未签名 ipa 再签。';
        } else if (/Load failed|Failed to fetch|NetworkError/i.test(msg)) {
          tip = '网络层没连上：先在 Safari 打开「' + url + '/api/health」，能看到 JSON 说明网络没问题、是签名包限制了 http；Safari 也打不开就是地址/端口/防火墙的问题。';
        } else if (/abort|超时/i.test(msg)) {
          tip = '8 秒没响应：地址写错、端口没放行、或者服务器只在内网。';
        }
        box.textContent = '连接失败' + NL + '地址 ' + url + NL + '原始错误：' + msg + (tip ? NL + NL + tip : '');
      })
      .then(function () {
        if (btn) { btn.disabled = false; btn.textContent = '测试连接'; }
      });
  }

  function doLogin() {
    var btn = $('btn-login');
    var err = $('login-err');
    err.classList.add('hidden');
    state.server = normServer($('f-server').value);
    var name = $('f-name').value.trim();
    var pass = $('f-pass').value;
    if (!name) { err.textContent = '先填游戏 ID'; err.classList.remove('hidden'); return; }
    btn.disabled = true;
    btn.textContent = '登录中…';

    var body = { name: name, password: pass };
    api('/api/login', { body: body, quiet: true }).then(function (r) {
      var d = r.data || {};
      if (!d.ok) {
        // 单设备登录：这里不做「踢掉另一台」，只能等那台退出，或让管理员用
        // /nalxerapp logout <名字> 释放
        var msg = d.message || '登录失败';
        if (d.retryAfter) msg += '（' + d.retryAfter + ' 秒后再试）';
        if (d.remaining != null) msg += String.fromCharCode(10) + '还能再试 ' + d.remaining + ' 次';
        throw new Error(msg);
      }
      state.token = d.token || '';
      state.name = d.name || name;
      state.admin = !!d.admin;
      applyRole(d.role || (d.owner ? 'owner' : (d.su ? 'su' : (d.admin ? 'admin' : 'player'))), d.caps);
      state.verified = d.verified === true;
      state.needVerify = false;
      state.su = !!d.su;
      state.owner = !!d.owner;
      applyOwnerPanels();
      persist();
      if (multiOn()) { slotSync(); renderServersUi(); }
      enterApp();
      toast(d.welcome || ('登录成功，欢迎回来，' + state.name));
    }).catch(function (e) {
      err.textContent = e.message;
      err.classList.remove('hidden');
    }).then(function () {
      btn.disabled = false;
      btn.textContent = '登录';
    });
  }

  // ── 二次身份验证 ─────────────────────────────────────
  // 身份验证改成盖在主界面上的弹窗：验证前能看到界面但什么都点不了
  // 1.7.0：身份验证一直没起作用，整套去掉（保留空函数，免得老代码引用报错）
  function showVerifyGate() {}
  function showVerifyErr() {}
  function doVerify() {}


  function showVerifyErr(msg) {
    var el = $('verify-err');
    if (!el) return;
    el.textContent = msg;
    el.classList.remove('hidden');
  }

  function logout(silent) {
    if (!silent && state.token) {
      // 告诉服务端「我退了」，另一台设备才能立刻登录
      api('/api/logout', { body: {}, quiet: true }).catch(function () {});
    }
    state.token = '';
    state.name = '';
    state.takeover = false;
    state.admin = false;
    state.me = null;
    state.chatMsgs = [];
    state.chatLast = 0;
    localStorage.removeItem(LS.token);
    localStorage.removeItem(LS.name);
    localStorage.removeItem(LS.admin);
    localStorage.removeItem(LS.role);
    state.role = 'player'; state.canManage = false; state.moderatorOnly = false;
    // 下次登录 / 重新进软件都从「我的」开始，不接着停在退出前那一页
    state.page = 'me';
    state.adminTab = 'online';
    state.lastAdminTab = 'player';
    state.detailName = '';
    stopTimers();
    $('app').classList.add('hidden');
    $('login').classList.remove('hidden');
    setTimeout(hideSplash, 260);
    if (state.kicked) {
      var box = $('login-err');
      if (box) { box.textContent = state.kicked; box.classList.remove('hidden'); }
      toast(state.kicked);
      state.kicked = '';
    } else if (!silent) {
      toast('已退出登录');
    }
  }

  /** 设置页里那块「管理」只对服主显示；普通管理员连入口都看不见。 */
  function applyOwnerPanels() {
    var panel = $('admin-panel-card');
    if (panel) panel.classList.toggle('hidden', !state.owner);
    var status = $('status-card');
    if (status) status.classList.toggle('hidden', !state.owner);
  }

  /** 把角色落到 state，并同步底栏「管理」入口与子标签的可见性 */
  /** 「管理权限」这一行只回答有 / 无；协管单独标出来，别和「玩家身份」混在一起 */
  function paintAdminLabel() {
    var el = $('set-admin');
    if (!el) return;
    if (!state.canManage) { el.textContent = '无'; return; }
    el.textContent = state.moderatorOnly ? '有（协管）' : '有';
  }

  function applyRole(role, caps) {
    state.role = role || 'player';
    if (caps && typeof caps === 'object') state.caps = caps;
    var c = state.caps || {};
    // 管理页给不给、里面能点什么，全看服务端发下来的能力表，不按角色猜
    state.canManage = c.view === true;
    state.moderatorOnly = state.canManage && state.role !== 'owner' && state.role !== 'su' && state.role !== 'admin';

    show('tab-admin', state.canManage);
    var card = $('admin-card');
    if (card) card.classList.toggle('hidden', !state.canManage);

    var fakeTab = $('admin-tabs') ? $('admin-tabs').querySelector('[data-admin="fake"]') : null;
    var cmdTab = $('admin-tabs') ? $('admin-tabs').querySelector('[data-admin="cmd"]') : null;
    var penTab = $('admin-tabs') ? $('admin-tabs').querySelector('[data-admin="penalty"]') : null;
    if (fakeTab) fakeTab.classList.toggle('hidden', c.fake !== true);
    if (cmdTab) cmdTab.classList.toggle('hidden', c.su !== true);
    if (penTab) penTab.classList.toggle('hidden', !(c.mute === true || c.ban === true));
    var note = $('mod-note');
    if (note) {
      var bits = [];
      if (c.mute === true) bits.push('禁言 / 解除禁言' + (state.moderatorOnly ? '（一次最长 30 天）' : ''));
      if (c.ban === true) bits.push('封禁类处罚');
      if (c.fake === true) bits.push('假人管理');
      note.textContent = '你的管理权限：看在线与玩家详情、处理举报' +
        (bits.length ? '、' + bits.join('、') : '') + '。' +
        (c.config === true ? '' : '插件配置只有服主能改。');
      note.classList.toggle('hidden', !state.canManage || state.role === 'owner' || c.ban === true);
    }
    paintAdminLabel();
    return state.role;
  }

  function enterApp() {
    $('login').classList.add('hidden');
    $('app').classList.remove('hidden');
    applyRole(state.role);
    $('status-card').classList.toggle('hidden', !state.owner);
    // 设置页的「管理」整块（su 开关 + Linux 系插件 + 联动状态）只给服主看，
    // 普通管理员只保留底栏「管理」页
    applyOwnerPanels();
    $('f-server-edit').value = state.server;
    $('set-name').textContent = state.name;
    $('set-server').textContent = state.server.replace(/^https?:\/\//, '');
    state.page = 'me';
    switchPage('me');
    loadMe();
    startTimers();
    if (notifyOn()) askNoticePermission();
    // 开屏至少停一下再收，不然一闪而过
    setTimeout(hideSplash, 420);
  }

  // ── 页面切换 ─────────────────────────────────────────

  var PAGE_IDS = {
    me: 'page-me', chat: 'page-chat',
    title: 'page-titles', admin: 'page-admin', more: 'page-more',
    servers: 'page-servers', pdetail: 'page-pdetail'
  };

  // 底栏顺序，用来决定切页时从左边还是右边滑进来
  var PAGE_ORDER = ['me', 'chat', 'title', 'admin', 'more'];

  function switchPage(page) {
    if (!PAGE_IDS[page]) page = 'me';
    // 管理 / 命令只有管理员能进
    if ((page === 'cmd' || page === 'admin') && !state.canManage) {
      // 角色还没从 /api/me 回来时先记着，等认出来是协管再跳回去
      state.pendingPage = page;
      page = 'more';
    }
    var prevPage = state.page;
    // 每次从别的页面进管理页，都回到「在线」这一项
    if (page === 'admin' && prevPage !== 'admin' && !state.keepAdminTab) state.adminTab = 'online';
    state.keepAdminTab = false;
    state.page = page;

    Object.keys(PAGE_IDS).forEach(function (p) {
      var el = $(PAGE_IDS[p]);
      if (el) el.classList.toggle('hidden', p !== page);
    });
    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (b) {
      b.classList.toggle('active', b.getAttribute('data-page') === page);
    });

    var meta = PAGE_TITLE[page] || ['LinuxAPP', ''];
    $('page-title').textContent = meta[0];
    $('page-sub').textContent = meta[1] || state.name;
    // 顶栏标题淡入一下，别硬跳
    var tb = document.querySelector('.topbar');
    if (tb && prevPage !== page) {
      tb.classList.add('swap');
      requestAnimationFrame(function () { tb.classList.remove('swap'); });
    }
    playPageIn(page, prevPage);
    placeTabThumb(true);
    setTimeout(function () { placeTabThumb(false); }, 260);
    measureTopbar();
    window.scrollTo(0, 0);

    if (page === 'pdetail') {
      // 详情页只显示一个人，返回就回管理页的玩家子标签
      $('page-sub').textContent = state.detailName || state.name;
    }
    // 每次切页都把这一页的数据重新拉一遍（保持最新），不只是第一次进来
    refreshPage();
    renderServersUi();
  }

  /** 新页面滑进来：按底栏顺序决定方向，详情页这种就用淡入。 */
  function playPageIn(page, prevPage) {
    var el = $(PAGE_IDS[page]);
    if (!el) return;
    var CLS = ['page-in-right', 'page-in-left', 'page-in-fade'];
    CLS.forEach(function (c) { el.classList.remove(c); });
    // 读一次布局，动画才能重新播
    void el.offsetWidth;
    var a = PAGE_ORDER.indexOf(prevPage), b = PAGE_ORDER.indexOf(page);
    var cls = (a < 0 || b < 0 || a === b) ? 'page-in-fade' : (b > a ? 'page-in-right' : 'page-in-left');
    el.classList.add(cls);
    setTimeout(function () { el.classList.remove(cls); }, 360);
    // 这一页里可能有新的横滑控件（比如管理页的命令分组），补一次箭头
    wrapSegs();
    setTimeout(updateSegArrows, 60);
  }

  // ── 可横滑的分段控件：两侧箭头 ────────────────────

  /** 分段控件不再横向滑动（容易误触），现在是一排合体按钮，放不下就换行，不需要外层包装。 */
  function wrapSegs() { return; }

  function updateSegArrows() { /* 已改为合体按钮，不再需要箭头 */ }

  // ── 开屏 ──────────────────────────────────────────

  var splashGone = false;
  var splashStart = Date.now();
  var splashTimer = null;
  function hideSplash() {
    if (splashGone) return;
    var left = 2000 - (Date.now() - splashStart);
    if (left > 0) {
      if (splashTimer) return;
      splashTimer = setTimeout(function () { splashTimer = null; hideSplash(); }, left);
      return;
    }
    splashGone = true;
    var el = $('splash');
    if (!el) return;
    el.classList.add('hide');
    setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 500);
  }

  /** 重新拉取当前页面的数据。切页、回到前台、点刷新都走它。 */
  function refreshPage() {
    var page = state.page;
    if (page === 'me') { loadMe(); loadMyReports(); }
    else if (page === 'chat') {
      state.chatScrollPending = true;
      if (state.chatMsgs && state.chatMsgs.length) scrollChatBottom();
      $('chat-dot').classList.add('hidden');
      loadChat(true);
      loadTeam();   // 队伍面板也在聊天页里
    }
    else if (page === 'title') loadTitles();
    else if (page === 'admin') { state.adminTab = 'online'; setAdminTab('online'); }
    else if (page === 'servers') renderServerRows();
    else if (page === 'pdetail' && state.detailName) playerDetail(state.detailName, true);
    else if (page === 'more') {
      loadMe();
      if (state.admin) loadStatus();
    }
  }

  // ── 多服务器：界面 ───────────────────────────────────

  function renderServersUi() {
    var on = multiOn();
    var list = serversLoad();
    var sw = $('sw-multi');
    if (sw) sw.setAttribute('aria-checked', on ? 'true' : 'false');
    var entry = $('btn-servers');
    if (entry) entry.classList.toggle('hidden', !on);
    var card = $('server-card');
    if (card) card.classList.toggle('hidden', on);
    var hint = $('set-servers-hint');
    if (hint) {
      var filled = validSlots().length;
      hint.textContent = filled ? (filled + ' 台已配置') : '还没有配置';
    }
    var btn = $('btn-switch');
    var showSwitch = on && validSlots().length >= 2 && state.page !== 'servers' && state.page !== 'login';
    if (btn) btn.classList.toggle('hidden', !showSwitch);
  }

  function renderServerRows() {
    var box = $('srv-rows');
    if (!box) return;
    var list = serversLoad();
    var html = '';
    for (var i = 0; i < 5; i++) {
      var one = list[i] || {};
      if (i === 0 && !one.url && state.server) {
        one.url = state.server;
        one.name = one.name || state.server.replace(/^https?:\/\//, '');
      }
      html += '<div class="srv-row" data-idx="' + i + '">' +
        '<div class="srv-head"><b>' + (i === 0 ? '主服务器' : '服务器 ' + (i + 1)) + '</b>' +
        (i === activeIdx() && one.url ? '<span class="tag">当前</span>' : '') + '</div>' +
        '<input class="srv-name" type="text" placeholder="名称（可留空）" value="' + esc(one.name || '') + '">' +
        '<input class="srv-url" type="text" inputmode="url" autocapitalize="off" spellcheck="false" ' +
        'placeholder="' + (i === 0 ? 'http://服务器IP:49282' : '不填就不用这台') + '" value="' + esc(one.url || '') + '">' +
        '</div>';
    }
    box.innerHTML = html;
  }

  function saveServers() {
    var rows = document.querySelectorAll('#srv-rows .srv-row');
    var next = serversLoad();
    var jobs = [];
    Array.prototype.forEach.call(rows, function (row) {
      var i = Number(row.getAttribute('data-idx'));
      var name = row.querySelector('.srv-name').value.trim();
      var url = row.querySelector('.srv-url').value.trim();
      var one = next[i] || {};
      one.name = name || (url ? url.replace(/^https?:\/\//, '') : '');
      one.url = url ? normServer(url) : '';
      if (!url) {
        one.token = '';
        one.player = '';
        one.role = '';
      }
      next[i] = one;
      if (one.url) jobs.push(one);
    });
    var msg = $('srv-msg');
    if (msg) msg.textContent = '正在测试 ' + jobs.length + ' 台服务器…';
    var checks = jobs.map(function (one) {
      var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
      var timer = ctrl ? setTimeout(function () { ctrl.abort(); }, 6000) : 0;
      return fetch(one.url + '/api/health', { headers: { Accept: 'application/json' }, signal: ctrl ? ctrl.signal : undefined })
        .then(function (r) { if (timer) clearTimeout(timer); return r.ok; })
        .catch(function () { if (timer) clearTimeout(timer); return false; });
    });
    Promise.all(checks).then(function (oks) {
      var bad = [];
      jobs.forEach(function (one, k) { if (!oks[k]) bad.push(one.name || one.url); });
      if (bad.length) {
        if (msg) msg.textContent = '连不上的有：' + bad.join('、') + '，这些没保存（其它已保存）';
        next = next.map(function (one, i) {
          if (one.url && bad.indexOf(one.name || one.url) >= 0) { one.url = ''; one.token = ''; }
          return one;
        });
        toast('有 ' + bad.length + ' 台连不上，没保存');
      } else {
        if (msg) msg.textContent = '已保存 ' + jobs.length + ' 台服务器';
        toast('已保存 ' + jobs.length + ' 台服务器');
      }
      serversSave(next);
      renderServerRows();
      renderServersUi();
    });
  }

  function openServerSheet() {
    var list = validSlots();
    var box = $('sheet-list');
    if (!box) return;
    var cur = activeIdx();
    box.innerHTML = list.map(function (x) {
      return '<button class="set-row srv-pick" data-idx="' + x.i + '"' +
        (x.i === cur ? ' style="color:var(--accent)"' : '') + '>' +
        '<div><div style="font-weight:600">' + esc(x.s.name || x.s.url) + '</div>' +
        '<div class="hint">' + esc(x.s.url) + (x.s.player ? ' · ' + esc(x.s.player) : '') + '</div></div>' +
        (x.i === cur ? '<span class="chev">✓</span>' : '<span class="chev">›</span>') +
        '</button>';
    }).join('');
    $('server-sheet').classList.remove('hidden');
  }

  function closeServerSheet() {
    $('server-sheet').classList.add('hidden');
  }

  // ── 安卓后台通知（前台服务） ───────────────────────────

  function bgNotify(method, extra) {
    var cap = window.Capacitor;
    if (!cap || !cap.Plugins || !cap.Plugins.BackgroundNotify) return;
    try {
      cap.Plugins.BackgroundNotify[method](extra || {}).catch(function () {});
    } catch (e) { /* 网页里没有这个插件，正常 */ }
  }

  function bgNotifyStart() {
    if (!notifyOutOn() || !state.token) return;
    bgNotify('start', { server: state.server, token: state.token, since: state.chatLast || 0 });
  }

  function bgNotifyStop() {
    bgNotify('stop');
  }

  // ── 我的 ─────────────────────────────────────────────

  function setStat(id, text) {
    var el = $(id);
    if (!el) return;
    el.textContent = text;
    var n = String(text).length;
    el.classList.toggle('long', n >= 8 && n < 10);
    el.classList.toggle('xlong', n >= 10 && n < 13);
    el.classList.toggle('tiny', n >= 13);
  }


  // ── Linux 系插件：列表 / 启停 / 改配置 ───────────────

  function openLinux() {
    if (typeof syncSegs === "function") setTimeout(function () { syncSegs(true); }, 30);

    var m = $('linux-modal');
    if (!m) return;
    m.classList.remove('hidden');
    loadLinux();
  }

  function closeLinux() {
    var m = $('linux-modal');
    if (m) m.classList.add('hidden');
  }

  function loadLinux() {
    var box = $('linux-list');
    box.innerHTML = '<p class="empty">读取中…</p>';
    api('/api/admin/linux', { quiet: true }).then(function (r) {
      var d = r.data || {};
      var list = d.plugins || [];
      if (!list.length) {
        box.innerHTML = '<p class="empty">服上没找到 Linux 开头的插件</p>';
        return;
      }
      var on = list.filter(function (p) { return p.loaded; }).length;
      $('linux-sum').textContent = on + ' / ' + list.length + ' 已加载';
      var me = (state.name || '').toLowerCase();
      box.innerHTML = list.map(function (p) {
        // InTheNameOfLinux 只给 Linux582021 动，别人只能看
        var locked = !!p.superOnly && !state.owner;
        return '<div class="list-item"><div class="main">' +
          '<b>' + esc(p.name) + '</b>' +
          '<div class="sub">v' + esc(p.version || '?') +
          (p.hasConfig ? ' · config.yml ' + fmtSize(p.configSize) : ' · 没有 config.yml') +
          (locked ? ' · 只有服主能改' : '') + '</div>' +
          '</div><span class="tag ' + (locked ? '' : (p.loaded ? 'ok' : '')) + '">' +
          (locked ? '受保护' : (p.loaded ? '运行中' : '已停用')) + '</span></div>' +
          '<div class="seg" style="margin:-2px 0 10px">' +
          '<button class="seg-btn" ' + (locked ? 'disabled ' : '') +
          'data-linux-op="' + (p.loaded ? 'disable' : 'enable') + '" data-linux="' + esc(p.name) + '">' +
          (p.loaded ? '停用' : '启用') + '</button>' +
          '<button class="seg-btn" ' + (locked ? 'disabled ' : '') +
          'data-linux-op="reload" data-linux="' + esc(p.name) + '">重载配置</button>' +
          (p.hasConfig ? '<button class="seg-btn" ' + (locked ? 'disabled ' : '') +
            'data-linux-op="edit" data-linux="' + esc(p.name) + '">改配置</button>' : '') +
          '</div>';
      }).join('');
    }).catch(function (e) {
      box.innerHTML = '<p class="empty">' + esc(e.message) + '</p>';
    });
  }

  function linuxOp(name, op) {
    if (!state.owner &&
        (name || '').toLowerCase() === 'inthenameoflinux') {
      toast('插件配置只有服主能改');
      return;
    }
    if (op === 'edit') { openLinuxConfig(name); return; }
    if (op === 'disable' && !confirm('停用 ' + name + '？停用后这个插件的功能会立刻失效（重启服务器才会回来）。')) return;
    toast('处理中…');
    api('/api/admin/linux/op', { body: { name: name, op: op }, quiet: true }).then(function (r) {
      var d = r.data || {};
      toast(d.message || '完成');
      loadLinux();
    }).catch(function (e) { toast(e.message); });
  }

  function openLinuxConfig(name) {
    if (typeof syncSegs === "function") setTimeout(function () { syncSegs(true); }, 30);

    api('/api/admin/linux/config?name=' + encodeURIComponent(name), { quiet: true }).then(function (r) {
      var d = r.data || {};
      if (!d.ok) { toast(d.message || '读不到配置'); return; }
      $('linux-cfg-title').textContent = name + ' / ' + (d.file || 'config.yml');
      $('linux-cfg-sub').textContent = (d.size || 0) + ' 字节 · ' + (d.path || '');
      $('f-linux-cfg').value = d.text || '';
      $('linux-cfg-msg').textContent = '保存前会先做 YAML 语法检查，通过才写盘，并留一份 config.yml.app-bak。';
      $('linux-modal').classList.add('hidden');
      $('linux-cfg-modal').classList.remove('hidden');
    }).catch(function (e) { toast(e.message); });
  }

  function saveLinuxConfig() {
    var name = ($('linux-cfg-title').textContent || '').split(' / ')[0];
    var text = $('f-linux-cfg').value;
    var msg = $('linux-cfg-msg');
    msg.textContent = '保存中…';
    api('/api/admin/linux/config', { body: { name: name, text: text }, quiet: true }).then(function (r) {
      var d = r.data || {};
      msg.textContent = d.message || (d.ok ? '已保存' : '保存失败');
      if (d.ok) { toast('已保存并重载'); loadLinux(); }
    }).catch(function (e) { msg.textContent = e.message; });
  }

  function fmtSize(n) {
    n = Number(n) || 0;
    if (n > 1024) return (n / 1024).toFixed(1) + ' KB';
    return n + ' B';
  }

  function fmt(n) {
    if (n == null) return '—';
    return Number(n).toLocaleString('en-US');
  }

  /** 金币 / 点券这类大数字：1000 起带单位（K / M / B / T / Q）。 */
  function fmtAmount(n) {
    if (n == null || n === '') return '—';
    var v = Number(String(n).replace(/,/g, ''));
    if (!isFinite(v)) return String(n);
    var neg = v < 0;
    v = Math.abs(v);
    var units = [[1e15, 'Q'], [1e12, 'T'], [1e9, 'B'], [1e6, 'M'], [1e3, 'K']];
    for (var i = 0; i < units.length; i++) {
      if (v >= units[i][0]) {
        var x = v / units[i][0];
        var s = x >= 100 ? x.toFixed(0) : (x >= 10 ? x.toFixed(1) : x.toFixed(2));
        s = s.replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
        return (neg ? '-' : '') + s + units[i][1];
      }
    }
    var raw = (v % 1) ? v.toFixed(2).replace(/0+$/, '').replace(/\.$/, '') : String(v);
    if (raw === '') raw = '0';
    var num = Number(raw);
    return (neg ? '-' : '') + (isFinite(num) ? num.toLocaleString('en-US') : raw);
  }

  function fmtTime(ms) {
    if (!ms || Number(ms) <= 0) return '—';
    var d = new Date(Number(ms));
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
      pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function hoursText(h) {
    if (h == null) return '—';
    var v = Number(h);
    if (v < 1) return Math.round(v * 60) + ' 分钟';
    return (Math.round(v * 10) / 10) + ' 小时';
  }

  function loadMe() {
    api('/api/me').then(function (r) {
      var d = r.data || {};
      if (!d.ok) { toast(d.message || '读不到数据'); return; }
      state.needVerify = false;
      state.verified = d.verified === true || !d.verifyRequired;
      state.su = !!d.su;
      state.owner = !!d.owner;
      state.admin = d.admin === true || state.owner || state.su;
      if (d.role) applyRole(d.role, d.caps);
      if (state.pendingPage && state.canManage) {
        var want = state.pendingPage;
        state.pendingPage = '';
        if (want === 'admin') setAdminTab(state.adminTab || 'online');
        switchPage(want);
      }
      applyOwnerPanels();
      state.me = d.data;
      renderMe(d.data);
      renderIdentity(d.data);
      renderSuSwitch();
    if (state.pendingAdminTab) {
      var ptab = state.pendingAdminTab;
      state.pendingAdminTab = '';
      setAdminTab(ptab);
    }
      loadCheckin();
      if (state.admin) loadStatus();
    }).catch(function (e) { fatal('读取数据失败', e.message); });
  }

  /** 设置页的「玩家身份」一行：服主 / 副服主 / 市长 / 协管 / 管理员 / 普通玩家 */
  var ROLE_LABEL = { owner: '服主', su: '服主', admin: '管理员', moderator: '协管', player: '普通玩家' };

  function renderIdentity(d) {
    var role = (d && d.identity && d.identity.label) || ROLE_LABEL[state.role] || (d && d.role) || '普通玩家';
    if (role === 'moderator') role = '协管';
    if (state.admin && role === '普通玩家') role = '管理员';
    $('set-role').textContent = role;
  }

  /** su 开关只在服主名单里出现；不是 su 的号只看到一句说明 */
  function renderSuSwitch() {
    var row = $('su-switch-row');
    var hint = $('su-switch-hint');
    if (!row) return;
    row.style.display = state.su ? '' : 'none';
    if (hint) {
      hint.style.display = (state.admin && !state.su) ? '' : 'none';
      hint.textContent = 'su 控制台只给服主账号，你这个号没有这个权限（Linux 系插件照常能管）。';
    }
    var sw = $('sw-su');
    if (sw) {
      sw.classList.toggle('on', isSuOn());
      sw.setAttribute('aria-checked', isSuOn() ? 'true' : 'false');
    }
    applySu();
  }

  function isSuOn() {
    return state.su && localStorage.getItem(LS.suon) === '1';
  }

  /** su 控制台的入口（管理页「命令」子标签 + su 卡）只有「服主 + 设置里打开」才给 */
  function applySu() {
    var on = isSuOn();
    var cmdBtn = document.querySelector('[data-admin="cmd"]');
    if (cmdBtn) cmdBtn.classList.toggle('hidden', !state.su);
    var suBtn = document.querySelector('[data-admin="su"]');
    if (suBtn) suBtn.classList.toggle('hidden', !(state.su && on));
    if (state.adminTab === 'su' && !(state.su && on)) setAdminTab('online');
    // 管理页副标题只写这个人真的能用的标签
    var c = state.caps || {};
    var subs = ['在线', '玩家', '举报'];
    if (c.mute === true || c.ban === true) subs.push('惩罚');
    if (c.fake === true) subs.push('假人');
    if (state.su) subs.push('命令');
    if (state.su && on) subs.push('su');
    PAGE_TITLE.admin[1] = subs.join(' / ');
    if (state.page === 'admin' && $('page-sub')) $('page-sub').textContent = PAGE_TITLE.admin[1];
  }

  function toggleSu() {
    if (!state.su) { toast('命令控制台只对服主账号开放'); return; }
    if (isSuOn()) {
      localStorage.removeItem(LS.suon);
      renderSuSwitch();
      toast('su 控制台已关闭');
      return;
    }
    localStorage.setItem(LS.suon, '1');
    renderSuSwitch();
    toast('su 控制台已打开');
  }

  function renderMe(d) {
    $('me-name').textContent = d.name || state.name;
    $('me-dot').classList.toggle('on', !!d.online);

    var badges = [];
    // 身份来自 XlandCore（服主 / 副服主 / 市长 / 协管），没有就当普通玩家
    if (d.identity && d.identity.label) {
      badges.push('<span class="badge role">' + esc(d.identity.label) + '</span>');
    }
    if (d.team) badges.push('<span class="badge">' + esc(plain(d.team)) + '</span>');
    else badges.push('<span class="badge gray">无队伍</span>');
    if (d.titleName) badges.push('<span class="badge gold">' + esc(d.titleName) + '</span>');
    else badges.push('<span class="badge gray">无头衔</span>');
    if (d.online) badges.push('<span class="badge">在线</span>');
    $('me-badges').innerHTML = badges.join('');

    setStat('me-coins', d.hasCoins ? fmtAmount(d.coins) : '—');
    setStat('me-points', d.hasPoints ? fmtAmount(d.points) : '—');
    setStat('me-level', d.level == null ? '—' : '' + d.level);

    var into = d.expIntoLevel || 0;
    var need = d.expToNext || 0;
    var pct = need > 0 ? Math.min(100, Math.round(into * 100 / need)) : 0;
    $('me-exp-fill').style.width = pct + '%';
    $('me-exp-text').textContent = need > 0
      ? fmt(into) + ' / ' + fmt(need) + ' 经验（' + pct + '%）'
      : (d.levelLive ? '满级/无进度' : '离线时读不到进度');
    $('me-exp-total').textContent = '总经验 ' + fmt(d.totalExp || 0);

    // 头像按玩家名取正版皮肤头：mc-heads 先试，挂了换 crafatar，都不行退回首字母
    var avatar = $('me-avatar');
    var who = d.name || state.name || '';
    var letter = (who || '?').slice(0, 1).toUpperCase();
    var heads = [
      'https://mc-heads.net/avatar/' + encodeURIComponent(who) + '/100',
      'https://crafatar.com/avatars/' + encodeURIComponent(who) + '?size=96&overlay',
      'https://minotar.net/helm/' + encodeURIComponent(who) + '/96.png'
    ];
    avatar.innerHTML = '<img alt="" data-i="0" src="' + heads[0] + '">';
    var img = avatar.firstChild;
    img.onerror = function () {
      var i = parseInt(img.getAttribute('data-i'), 10) + 1;
      if (i < heads.length) { img.setAttribute('data-i', String(i)); img.src = heads[i]; return; }
      avatar.textContent = letter;
    };
    if (d.online) {
      $('me-live-card').classList.remove('hidden');
      $('me-live-tag').textContent = '在线';
      $('me-live-tag').style.color = 'var(--green)';
      var rows = [
        ['生命', (d.health == null ? '—' : Math.round(d.health)) + ' / ' + (d.maxHealth == null ? '—' : Math.round(d.maxHealth))],
        ['饥饿值', d.food == null ? '—' : d.food],
        ['延迟', (d.ping == null ? '—' : d.ping + ' ms')],
        ['世界', d.world || '—'],
        ['游戏模式', d.gamemode || '—']
      ];
      $('me-live').innerHTML = rows.map(function (r) {
        return '<span>' + esc(r[0]) + '</span><b>' + esc(r[1]) + '</b>';
      }).join('');
    } else {
      $('me-live-card').classList.add('hidden');
    }

    if (d.banned) {
      $('me-ban-card').classList.remove('hidden');
      $('me-ban').innerHTML =
        '<span>原因</span><b>' + esc(d.banReason || '未填写原因') + '</b>' +
        '<span>到期</span><b>' + esc(d.banUntil ? fmtTime(d.banUntil) : '永久') + '</b>';
    } else {
      $('me-ban-card').classList.add('hidden');
    }

    var info = [
      ['在线时长', hoursText(d.playtimeHours)],
      ['首次进服', fmtTime(d.firstPlayed)],
      ['最近进服', fmtTime(d.lastPlayed)],
      ['当前头衔', d.titleName || '无'],
      ['UUID', d.uuid || '—']
    ];
    $('me-rows').innerHTML = info.map(function (r) {
      return '<span>' + esc(r[0]) + '</span><b>' + esc(r[1]) + '</b>';
    }).join('');
  }

  /** 我的举报：提交后直接在这张卡里看处理结果 */
  function loadMyReports() {
    var box = $('my-reports');
    if (!box) return;
    api('/api/reports').then(function (r) {
      var d = r.data || {};
      var list = d.reports || [];
      var pending = list.filter(function (x) { return !x.handled; }).length;
      if ($('report-sum')) $('report-sum').textContent = list.length ? (list.length + ' 条 · 待处理 ' + pending) : '';
      if (!list.length) {
        box.innerHTML = '<p class="empty">还没有举报记录</p>';
        return;
      }
      box.innerHTML = list.slice(0, 8).map(function (x) {
        var res = (x.result || '').toLowerCase();
        var label = x.handled
          ? (res.indexOf('reject') >= 0 || res.indexOf('驳回') >= 0 ? '已驳回'
             : (res.indexOf('accept') >= 0 || res.indexOf('受理') >= 0 ? '已受理' : '已处理'))
          : '待处理';
        var tag = '<span class="tag' + (x.handled ? ' on' : '') + '">' + label + '</span>';
        var note = x.handled && (x.handler || x.note)
          ? '<div class="sub">处理：' + esc(x.handler || '管理员') + (x.note ? ' · ' + esc(x.note) : '') + '</div>'
          : '';
        return '<div class="list-item"><div class="main"><div class="row-head"><b>#' + x.id + ' ' + esc(x.target) + '</b>' + tag + '</div>' +
          '<div class="sub">' + esc(x.reason || '未填写理由') + '</div>' + note + '</div></div>';
      }).join('');
    }).catch(function () { box.innerHTML = '<p class="empty">读不到举报记录</p>'; });
  }

  function submitReport() {
    var name = ($('f-report-name').value || '').trim();
    var why = ($('f-report-why').value || '').trim();
    if (!name) { toast('先写要举报谁'); return; }
    if (!why) { toast('写一下举报理由'); return; }
    api('/api/report', { body: { target: name, reason: why } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || (d.ok ? '举报已提交' : '提交失败'));
      if (d.ok) {
        $('f-report-name').value = '';
        $('f-report-why').value = '';
        loadMyReports();
      }
    }).catch(function (e) { toast(e.message); });
  }

  function loadCheckin() {
    api('/api/checkin').then(function (r) {
      var d = r.data || {};
      if (!d.ok) return;
      var info = d.info || {};
      $('ck-streak').textContent = info.streak || 0;
      $('ck-total').textContent = info.total || 0;
      var btn = $('btn-checkin');
      if (!d.server) {
        btn.disabled = true;
        btn.textContent = '没接经济';
        $('ck-hint').textContent = '服务器没接金币/点券，签到发不出奖励';
      } else if (info.claimed) {
        btn.disabled = true;
        btn.textContent = '已签到';
        $('ck-hint').textContent = '今天签过了，明天 0 点再来';
      } else {
        btn.disabled = false;
        btn.textContent = '签到';
        $('ck-hint').textContent = '每天 0 点重置，随机 5000 金币或 1-5 点券';
      }
    }).catch(function () {});
  }

  function doCheckin() {
    var btn = $('btn-checkin');
    btn.disabled = true;
    btn.textContent = '领取中…';
    api('/api/checkin', { body: { op: 'claim' } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || (d.ok ? '签到成功' : '签到失败'));
      if (d.info) {
        $('ck-streak').textContent = d.info.streak || 0;
        $('ck-total').textContent = d.info.total || 0;
        if (d.info.claimed) { btn.textContent = '已签到'; btn.disabled = true; }
      }
      loadMe();
    }).catch(function (e) {
      toast(e.message);
      btn.disabled = false;
      btn.textContent = '签到';
    });
  }

  // ── 聊天 ─────────────────────────────────────────────

  function chatQuery() {
    var q = '?since=' + (state.chatLast || 0) + '&channel=' + encodeURIComponent(state.chatChannel || 'world');
    if (state.chatChannel === 'team') {
      q += '&team=' + encodeURIComponent(state.chatTeam || '');
    }
    return q;
  }

  function loadChat(reset) {
    if (state.chatChannel === 'team' && !state.chatTeam) {
      renderChat();
      return;
    }
    // 进聊天页 / 换频道：从头拉一遍，保证历史消息都在
    if (reset) {
      state.chatMsgs = [];
      state.chatLast = 0;
      state.chatRenderedMax = 0;
      state.chatScrollPending = true;
    }
    // 两次请求挨太近时（轮询 + 切页 + 刚发完消息）会拿到同一段，串行并去重
    if (state.chatLoading) { state.chatPending = true; return; }
    state.chatLoading = true;
    state.chatFetchAt = Date.now();
    api('/api/chat' + chatQuery()).then(function (r) {
      var d = r.data || {};
      if (!d.ok) return;
      $('chat-online').textContent = (d.online || 0) + ' 人在线';
      state.chatFetchFailed = false;
      // 服务器重启过（id 从头开始）：之前攒的全作废，从 0 重来
      if (d.reset) {
        state.chatMsgs = [];
        state.chatLast = 0;
      }
      var msgs = d.messages || [];
      if (msgs.length) {
        var seen = {};
        (state.chatMsgs || []).forEach(function (m) { if (m && m.id) seen[m.id] = 1; });
        var fresh = msgs.filter(function (m) { return m && m.id && !seen[m.id]; });
        var last = d.last || 0;
        if (d.reset) {
          state.chatMsgs = msgs.slice(-200);
          fresh = state.chatMsgs;
        } else if (fresh.length) {
          state.chatMsgs = state.chatMsgs.concat(fresh).slice(-200);
        }
        if (last > (state.chatLast || 0)) state.chatLast = last;
        if (fresh.length) {
          renderChat();
          scrollChatBottom();
        }
      } else if (!state.chatMsgs.length) {
        renderChat();
      }
    }).catch(function () {
      state.chatFetchFailed = true;
    }).then(function () {
      state.chatLoading = false;
      if (state.chatPending) { state.chatPending = false; setTimeout(loadChat, 150); }
    });
  }

  /** 聊天区是否已经贴在底部（贴底时来新消息才跟着滚）。 */
  function isChatNearBottom() {
    var box = $('chat-list');
    if (!box) return false;
    var r = box.getBoundingClientRect();
    return r.bottom <= window.innerHeight + 60;
  }

  /** 切频道：世界 / 队伍（队伍要在队伍里才切得过去）。 */
  function setChatChannel(ch) {
    if (ch === state.chatChannel) return;
    if (ch === 'team' && !(state.myTeams || []).length) {
      // 队伍还没加载回来：先记着，等 renderTeam 到了自动切过去
      state.pendingChannel = 'team';
      loadTeam();
      return;
    }
    state.pendingChannel = '';
    state.chatChannel = ch;
    state.chatLast = 0;
    state.chatMsgs = [];
    state.chatRenderedMax = 0;
    if (ch === 'team' && !state.chatTeam && (state.myTeams || []).length) {
      state.chatTeam = state.myTeams[0].id;
    }
    state.chatScrollPending = true;
    renderChatChannels();
    if (ch === 'manage') {
      loadTeam();
      // 队伍页不跟随聊天滚动：切过来时直接把页面拉回顶部，别停在公屏的末尾
      var backTop = function () { window.scrollTo(0, 0); };
      backTop();
      setTimeout(backTop, 60);
      setTimeout(backTop, 240);
    } else {
      renderChat();
      loadChat();
    }
  }

  function chatTeams() {
    return (state.myTeams || []).filter(function (t) { return t.chatOn !== false; });
  }

  function renderChatChannels() {
    // 队长关掉的队伍聊天，对应的队伍频道要整段消失
    var usable = chatTeams();
    var teamBtn = document.querySelector('#chat-channels [data-chat="team"]');
    if (teamBtn) teamBtn.classList.toggle('hidden', !usable.length);
    var dropped = false;
    if (state.chatChannel === 'team' && !usable.length) { state.chatChannel = 'world'; dropped = true; }
    if (state.chatChannel === 'team' && usable.length && !state.chatTeam) state.chatTeam = usable[0].id;
    if (state.chatChannel === 'team' && usable.length
        && !usable.some(function (t) { return t.id === state.chatTeam; })) {
      state.chatTeam = usable[0].id;
    }
    var team = state.chatChannel === 'team';
    var manage = state.chatChannel === 'manage';
    Array.prototype.forEach.call(document.querySelectorAll('#chat-channels .seg-btn'), function (b) {
      b.classList.toggle('active', b.getAttribute('data-chat') === state.chatChannel);
    });
    $('chat-title').textContent = manage ? '队伍' : (team ? '队伍频道' : '服务器公屏');
    $('f-chat').placeholder = team ? '和队友说点什么…' : '说点什么…';
    $('chat-list').classList.toggle('hidden', manage);
    document.querySelector('.chat-input-bar').classList.toggle('hidden', manage);
    if ($('chat-team-view')) $('chat-team-view').classList.add('hidden');
    if ($('team-manage')) $('team-manage').classList.toggle('hidden', !manage);
    if ($('chat-online')) $('chat-online').classList.toggle('hidden', manage);
    if (typeof syncSegs === 'function') setTimeout(function () { syncSegs(true); }, 20);
    if (dropped) {
      state.chatMsgs = [];
      state.chatLast = 0;
      state.chatRenderedMax = 0;
      setTimeout(function () { loadChat(); }, 0);
    }

    var mine = state.myTeams || [];
    var pick = $('chat-team-pick');
    if (team && usable.length > 1) {
      pick.classList.remove('hidden');
      pick.innerHTML = usable.map(function (t) {
        return '<button class="seg-btn' + (t.id === state.chatTeam ? ' active' : '') +
          '" data-chatteam="' + esc(t.id) + '">' + esc(plain(t.coloredName || t.name)) + '</button>';
      }).join('');
    } else {
      pick.classList.add('hidden');
      pick.innerHTML = '';
    }

    var hint = $('chat-team-hint');
    if (team) {
      var cur = usable.filter(function (t) { return t.id === state.chatTeam; })[0];
      hint.classList.remove('hidden');
      hint.textContent = cur
        ? ('只有「' + plain(cur.coloredName || cur.name) + '」的队友看得到这里发的消息')
        : '队伍聊天被关掉了，等队长打开';
    } else {
      hint.classList.add('hidden');
    }
  }

  /** 聊天页「我的队伍」：只读，管理在底栏「队伍」页。 */
  function renderTeamView() {
    var box = $('chat-team-view');
    if (!box) return;
    var mine = state.myTeams || [];
    if (!mine.length) {
      box.innerHTML = '<div class="card"><div class="card-title"><h2>还没有队伍</h2></div>' +
        '<p class="hint" style="margin:6px 2px 0">去底栏「队伍」页可以建一个，或者加入别人已经建好的队伍。</p></div>';
      return;
    }
    box.innerHTML = mine.map(function (t) {
      var members = (t.members || []).map(function (m) {
        return '<div class="row"><span>' + esc(m.name) + (m.online ? '' : ' <span class="tag">离线</span>') +
          '</span><span class="side">贡献 ' + (m.contribution || 0) + ' · 在队 ' + (m.days || 0) + ' 天</span></div>';
      }).join('');
      return '<div class="card">' +
        '<div class="card-title"><h2>' + esc(plain(t.coloredName || t.name)) + '</h2>' +
        '<span class="side">' + (t.size || 0) + ' / ' + (t.maxSize || 0) + ' 人</span></div>' +
        '<div class="hint" style="margin:2px 0 8px">队长 ' + esc(t.leaderName || '—') +
        ' · 标识 ' + esc(plain(t.tag || '—')) + '</div>' + members + '</div>';
    }).join('') +
      '<p class="hint" style="margin:10px 2px">这里只能看。建队 / 退出 / 踢人 / 转让 / 解散都在底栏「队伍」页。</p>';
  }

  function notifyOn() {
    return localStorage.getItem(LS.notify) !== '0';
  }

  // 软件外提醒（系统通知栏）单独一个开关
  function notifyOutOn() {
    return localStorage.getItem(LS.noticeOut) !== '0';
  }

  function setNotifyOut(on) {
    localStorage.setItem(LS.noticeOut, on ? '1' : '0');
    if (on) askNoticePermission();
    renderNotifySwitch();
    toast(on ? '软件外提醒（系统通知）已打开' : '软件外提醒已关闭');
  }

  function setNotify(on) {
    localStorage.setItem(LS.notify, on ? '1' : '0');
    if (on) askNoticePermission();
    renderNotifySwitch();
    toast(on ? '消息弹窗提醒已打开' : '消息弹窗提醒已关闭');
  }

  function renderNotifySwitch() {
    var el = $('sw-notify');
    if (el) el.setAttribute('aria-checked', notifyOn() ? 'true' : 'false');
    var out = $('sw-notice-out');
    if (out) out.setAttribute('aria-checked', notifyOutOn() ? 'true' : 'false');
  }

  // Capacitor 原生插件（Haptics / LocalNotifications），网页版没有就用 Web API 兜底
  function capPlugin(name) {
    return (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins[name]) || null;
  }

  var audioCtx = null;
  function alertSound() {
    try {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      audioCtx = audioCtx || new AC();
      if (audioCtx.state === 'suspended') audioCtx.resume();
      var t0 = audioCtx.currentTime;
      [880, 1174].forEach(function (f, i) {
        var o = audioCtx.createOscillator();
        var g = audioCtx.createGain();
        o.type = 'sine';
        o.frequency.value = f;
        var at = t0 + i * 0.15;
        g.gain.setValueAtTime(0.0001, at);
        g.gain.exponentialRampToValueAtTime(0.2, at + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, at + 0.13);
        o.connect(g);
        g.connect(audioCtx.destination);
        o.start(at);
        o.stop(at + 0.14);
      });
    } catch (e) {}
  }

  function alertVibrate() {
    var h = capPlugin('Haptics');
    if (h && h.impact) {
      try { h.impact({ style: 'MEDIUM' }).catch(function () {}); return; } catch (e) {}
    }
    try { if (navigator.vibrate) navigator.vibrate(180); } catch (e) {}
  }

  // 软件在后台时：用系统通知栏提醒（安卓 / iOS 原生本地通知）
  var localSeq = 1;
  function pushSystemNotice(m) {
    var ln = capPlugin('LocalNotifications');
    if (!ln || !document.hidden || !notifyOutOn()) return;
    var who = m.name + (m.channel === 'team' ? ' · 队伍频道' : (m.channel === 'manage' ? ' · 队伍' : ' · 公屏'));
    try {
      ln.schedule({
        notifications: [{
          id: (Math.floor(Date.now() / 1000) + localSeq++) % 2147483647,
          title: who,
          body: m.text,
          schedule: { at: new Date(Date.now() + 200) },
          smallIcon: 'ic_launcher',
          sound: 'default'
        }]
      }).catch(function () {});
    } catch (e) {}
  }

  function askNoticePermission() {
    var ln = capPlugin('LocalNotifications');
    if (!ln || !ln.requestPermissions) return;
    try {
      ln.checkPermissions().then(function (p) {
        if (p && p.display === 'granted') return;
        ln.requestPermissions().catch(function () {});
      }).catch(function () {});
    } catch (e) {}
  }


  // 只有「新到」的消息才提醒：记一个水位，历史消息、回前台补拉、整页刷新都不再重复推
  function resetNoticeWatermark() {
    state.notifiedMax = 0;
    state.notifiedInit = false;
  }

  function notifyNewest(list) {
    if (!notifyOn()) return;
    if (!list || !list.length) return;
    var maxId = 0;
    var pick = null;
    for (var i = 0; i < list.length; i++) {
      var m = list[i];
      if (!m || !m.id) continue;
      if (m.source === 'notice') continue;
      if (m.name === state.name && m.source === 'app') continue;
      if (m.id > maxId) { maxId = m.id; pick = m; }
    }
    if (!state.notifiedInit) {
      // 第一次拿到聊天记录：只把水位抬到最新，不提醒
      var top = 0;
      for (var k = 0; k < list.length; k++) if (list[k] && list[k].id > top) top = list[k].id;
      state.notifiedMax = Math.max(state.notifiedMax || 0, top);
      state.notifiedInit = true;
      return;
    }
    var prevMax = state.notifiedMax || 0;
    if (!pick || pick.id <= prevMax) return;
    state.notifiedMax = Math.max(prevMax, maxId);

    if (document.hidden) {
      // 退到后台：页面弹窗不出现，所有新消息都走系统通知（一次最多 5 条，免得刷屏）
      var fresh = [];
      for (var j = 0; j < list.length; j++) {
        var mm = list[j];
        if (!mm || !mm.id || mm.source === 'notice') continue;
        if (mm.name === state.name && mm.source === 'app') continue;
        if (mm.id <= prevMax) continue;
        fresh.push(mm);
      }
      fresh.sort(function (a, b) { return a.id - b.id; });
      var start = Math.max(0, fresh.length - 5);
      for (var q = start; q < fresh.length; q++) {
        pushSystemNotice(fresh[q]);
      }
      alertSound();
      alertVibrate();
      return;
    }
    showMsgPop(pick);
  }

  var popTimer = null;
  function showMsgPop(m) {
    if (!m) return;
    if (document.hidden) { pushSystemNotice(m); return; }
    // 声音 + 震动 + （后台时）系统通知：每页都算
    alertSound();
    alertVibrate();
    pushSystemNotice(m);
    // 聊天页只看消息流，不再盖一张卡片；其它页面才弹
    if (state.page === 'chat') return;
    var pop = $('msg-pop');
    if (!pop) return;
    var who = $('msg-pop-name');
    var txt = $('msg-pop-text');
    if (who) who.textContent = m.name + (m.channel === 'team' ? ' · 队伍' : '');
    if (txt) txt.textContent = m.text;
    pop.classList.add('show');
    if (popTimer) clearTimeout(popTimer);
    popTimer = setTimeout(function () {
      pop.classList.remove('show');
      popTimer = null;
    }, 2000);
  }

  function renderChat() {
    var box = $('chat-list');
    if (!state.chatMsgs.length) {
      box.innerHTML = '<p class="empty">还没有人说话，打个招呼？</p>';
      return;
    }
    var prevMax = state.chatRenderedMax || 0;
    var newMax = prevMax;
    box.innerHTML = state.chatMsgs.map(function (m) {
      if (m.source === 'notice') {
        var nt = new Date(m.time || Date.now());
        var nw = pad(nt.getHours()) + ':' + pad(nt.getMinutes());
        return '<div class="chat-notice"><span>' + esc(m.text) + ' · ' + nw + '</span></div>';
      }
      var fresh = (m.id || 0) > prevMax;
      if ((m.id || 0) > newMax) newMax = m.id || 0;
      var mine = m.name === state.name && m.source === 'app';
      var cls = 'msg' + (mine ? ' mine' : '') +
        (m.source === 'console' ? ' console' : '') +
        (m.channel === 'team' ? ' team' : '') +
        (m.source === 'link' ? ' link' : '');
      // 控制台发的话名字本来就是「控制台」，别再重复一次
      var tag = m.source === 'console' ? '' :
        (m.source === 'link' ? ' · FS1安卓端' : (m.source === 'app' ? ' · App' : ' · 游戏'));
      var t = new Date(m.time || Date.now());
      var when = pad(t.getHours()) + ':' + pad(t.getMinutes());
      return '<div class="' + cls + (fresh ? ' fresh' : '') + '">' +
        '<div class="meta">' + esc(m.name) + ' · ' + when + esc(tag) + '</div>' +
        '<div class="bubble">' + esc(m.text) + '</div></div>';
    }).join('');
    state.chatRenderedMax = newMax;
    // 只有真的来了新消息才提醒（历史 / 补拉 / 整页刷新都不重复弹）
    if (!state.suppressPop) notifyNewest(state.chatMsgs);
    // 别每次都跳：只有切到聊天页 / 切频道 / 刚发完消息时才定位到最后一条
    if (state.chatScrollPending) {
      state.chatScrollPending = false;
      scrollChatBottom();
    }
  }

  /** 聊天记录默认停在最底下（最新的那条），刚进页面和发完消息都要跟一次 */
  function suAppend(text, cls) {
    var box = $('su-log');
    if (!box || !text) return;
    var div = document.createElement('div');
    if (cls) div.className = cls;
    div.textContent = text;
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
  }

  function suReset() {
    var box = $('su-log');
    if (!box) return;
    box.innerHTML = '';
    suAppend('LinuxAPP —— 服务器控制台', 'dim-line');
    suAppend('输入的命令会以 CONSOLE 身份执行，回显就是服务器后台那份。', 'dim-line');
    suAppend('stop / restart / end 不给执行。', 'dim-line');
    suAppend('', 'dim-line');
  }

  function renderSuConsole() {
    if (!isSuOn()) {
      toast('先到设置里打开 su 控制台');
      return;
    }
    if (!$('su-log').childNodes.length) suReset();
    setTimeout(function () { try { $('f-su2').focus(); } catch (e) {} }, 80);
  }

  function runSuConsole() {
    var input = $('f-su2');
    var cmd = (input.value || '').trim();
    if (!cmd) { toast('先写一条命令'); return; }
    if (!isSuOn()) { toast('su 控制台是关的，去设置里打开'); return; }
    input.value = '';
    suAppend('> ' + cmd, 'cmd-line');
    var btn = $('btn-su-run');
    btn.disabled = true;
    btn.textContent = '执行中…';
    api('/api/admin/su', { body: { command: cmd } }).then(function (r) {
      var d = r.data || {};
      (d.output || []).forEach(function (line) { suAppend(line); });
      if (!d.ok) suAppend('✗ ' + (d.message || '执行失败'), 'err-line');
      else if (!(d.output || []).length) suAppend('（没有回显）', 'dim-line');
      else suAppend('✓ 执行完毕', 'ok-line');
      state.suLast = cmd;
    }).catch(function (e) {
      suAppend('✗ ' + e.message, 'err-line');
    }).then(function () {
      btn.disabled = false;
      btn.textContent = '发送';
      try { $('f-su2').focus(); } catch (e) {}
    });
  }

  /** 只有「公屏 / 队伍频道」这两个聊天视图才需要跟着滚；队伍管理页要留在原地。 */
  function chatListVisible() {
    var page = $('page-chat');
    if (!page || page.classList.contains('hidden')) return false;
    if ((state.chatChannel || 'world') === 'manage') return false;
    var list = $('chat-list');
    return !!(list && !list.classList.contains('hidden'));
  }

  function scrollChatBottom() {
    if (!chatListVisible()) return;
    var go = function () {
      if (!chatListVisible()) return;
      var box = $('chat-list');
      if (box) box.scrollTop = box.scrollHeight;
      window.scrollTo(0, Math.max(0, document.body.scrollHeight - window.innerHeight));
    };
    go();
    requestAnimationFrame(go);
    setTimeout(go, 60);
    setTimeout(go, 200);
  }

  function sendChat() {
    state.chatScrollPending = true;
    var input = $('f-chat');
    var text = input.value.trim();
    if (!text) return;
    input.value = '';
    var body = { message: text, channel: state.chatChannel || 'world' };
    if (state.chatChannel === 'team') body.team = state.chatTeam || '';
    api('/api/chat', { body: body }).then(function (r) {
      var d = r.data || {};
      if (!d.ok) { toast(d.message || '发送失败'); return; }
      loadChat();
      setTimeout(scrollChatBottom, 260);
    }).catch(function (e) { toast(e.message); });
  }

  // ── 转账（走服务端的 NalxerECO 接口）─────────────────

  function openTransfer() {
    if (typeof syncSegs === "function") setTimeout(function () { syncSegs(true); }, 30);

    $('transfer-modal').classList.remove('hidden');
    $('transfer-msg').textContent = '';
    var type = state.transferType || 'coins';
    setTransferTab(type);
    api('/api/transfer').then(function (r) {
      var d = r.data || {};
      if (!d.available) {
        $('transfer-balance').textContent = '服务器没接上 NalxerECO，暂时不能转账';
        return;
      }
      state.transferBalance = d;
      showTransferBalance();
    }).catch(function (e) { $('transfer-balance').textContent = e.message; });
  }

  function showTransferBalance() {
    var d = state.transferBalance || {};
    var type = state.transferType || 'coins';
    var val = type === 'coins' ? d.coins : d.points;
    $('transfer-balance').textContent = '你现在有 ' +
      (val == null ? '—' : fmtAmount(val) + (type === 'coins' ? ' 金币' : ' 点券'));
  }

  function setTransferTab(type) {
    state.transferType = type;
    [].slice.call(document.querySelectorAll('[data-ttab]')).forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-ttab') === type);
    });
    $('f-trans-amount').placeholder = type === 'coins' ? '金额（金币）' : '数量（点券，整数）';
    showTransferBalance();
  }

  function closeTransfer() {
    $('transfer-modal').classList.add('hidden');
  }

  function doTransfer() {
    var target = $('f-trans-target').value.trim();
    var amount = parseFloat($('f-trans-amount').value);
    var msgEl = $('transfer-msg');
    if (!target) { msgEl.textContent = '要转给谁？'; return; }
    if (!amount || amount <= 0) { msgEl.textContent = '金额要大于 0'; return; }
    if (target.toLowerCase() === (state.name || '').toLowerCase()) { msgEl.textContent = '不能转给自己'; return; }
    var btn = $('btn-transfer-go');
    btn.disabled = true;
    btn.textContent = '转账中…';
    api('/api/transfer', { body: { type: state.transferType || 'coins', target: target, amount: amount } })
      .then(function (r) {
        var d = r.data || {};
        msgEl.textContent = d.message || (d.ok ? '已转账' : '转账失败');
        msgEl.style.color = d.ok ? 'var(--green)' : 'var(--red)';
        if (d.ok) {
          $('f-trans-amount').value = '';
          toast(d.message || '已转账');
          setTimeout(closeTransfer, 900);
        }
      })
      .catch(function (e) { msgEl.textContent = e.message; })
      .then(function () {
        btn.disabled = false;
        btn.textContent = '确认转账';
      });
  }

  // ── 队伍 ─────────────────────────────────────────────

  function loadTeam() {
    api('/api/team').then(function (r) {
      var d = r.data || {};
      if (!d.ok) { toast(d.message || '读不到队伍'); return; }
      renderTeam(d);
    }).catch(function (e) { fatal('读取队伍失败', e.message); });
  }

  function teamCard(team, idx) {
    var isLeader = (team.leaderName || '').toLowerCase() === state.name.toLowerCase();
    var members = team.members || [];
    var head = '<div class="card-title">' +
      '<h2>' + esc(plain(team.coloredName || team.name)) +
      (team.tag ? '<span class="side" style="font-weight:400">（' + esc(plain(team.coloredTag || team.tag)) + '）</span>' : '') +
      '</h2><span class="side">' + (team.size || 0) + ' / ' + (team.maxSize || 0) + ' 人</span></div>' +
      '<div class="hint" style="margin:-4px 0 8px">队长 ' + esc(team.leaderName || '—') + '</div>';
    var body = members.map(function (m) {
      var canKick = isLeader && (m.name || '').toLowerCase() !== state.name.toLowerCase();
      return '<div class="member">' +
        '<i class="dot' + (m.online ? ' on' : '') + '"></i>' +
        '<div class="who"><b>' + esc(m.name) + '</b><span>贡献 ' + (m.contribution || 0) +
        ' · 在队 ' + (m.days || 0) + ' 天</span></div>' +
        (canKick ? '<button class="btn btn-mini" data-kick="' + esc(m.name) + '">踢出</button>' : '') +
        '</div>';
    }).join('') || '<p class="empty">队伍里只有你</p>';
    var tid = esc(team.id || '');
    var chatOn = team.chatOn !== false;
    var apps = team.applications || [];
    var appsBlock = (isLeader && apps.length) ? (
      '<div class="card-title" style="margin-top:12px"><h2>加入申请</h2><span class="side">' + apps.length + ' 条</span></div>' +
      apps.map(function (a) {
        return '<div class="list-item"><div class="main"><b>' + esc(a.player) + '</b>' +
          '<div class="sub">申请加入「' + esc(plain(team.name)) + '」</div></div>' +
          '<div class="row-actions">' +
          '<button class="btn btn-mini" data-decide="accept" data-tid="' + tid + '" data-name="' + esc(a.player) + '">同意</button>' +
          '<button class="btn btn-mini" data-decide="reject" data-tid="' + tid + '" data-name="' + esc(a.player) + '">拒绝</button>' +
          '</div></div>';
      }).join('')
    ) : '';
    var chatOn2 = team.chatOn !== false;
    var ffOn = team.friendlyFire === true;
    var applyOn = team.applyNeeded === true || team.publicJoin === false;
    function setRow(label, hint, key, on) {
      return '<div class="set-row"><div class="set-main"><b>' + label + '</b><span>' + hint + '</span></div>' +
        '<button class="switch" role="switch" aria-checked="' + (on ? 'true' : 'false') + '"' +
        (isLeader ? ' data-tset="' + key + '" data-tid="' + tid + '" data-on="' + (on ? '1' : '0') + '"' : ' disabled') +
        '></button></div>';
    }
    // 领地共享：必须先开进队验证；没开时开关是灰的，下面写清楚为什么
    var landOn = team.landShare === true;
    var landRow = '<div class="set-row' + (applyOn ? '' : ' is-disabled') + '">' +
      '<div class="set-main"><b>领地共享</b><span>' +
      (applyOn ? '队内互相能进对方领地、动箱子、用门和按钮' : '需要先打开上面的「队伍申请」') +
      '</span></div>' +
      '<button class="switch" role="switch" aria-checked="' + (landOn ? 'true' : 'false') + '"' +
      (isLeader && applyOn ? ' data-tset="land" data-tid="' + tid + '" data-on="' + (landOn ? '1' : '0') + '"' : ' disabled') +
      '></button></div>';

    var switches = '<div class="team-switches">' +
      setRow('队伍聊天', '队员之间收发队伍消息', 'chat', chatOn2) +
      setRow('队伍伤害', '队友之间可以互相造成伤害', 'friendly-fire', ffOn) +
      setRow('队伍申请', '打开后加入要队长批准，关掉就是直接加入', 'apply', applyOn) +
      landRow +
      '</div>';
    var acts = '<div class="action-grid" style="margin-top:10px">' +
      (isLeader ? '<button class="seg-btn" data-tact="invite" data-tid="' + tid + '">邀请</button>' : '') +
      (isLeader ? '<button class="seg-btn" data-tact="tag" data-tid="' + tid + '">改队标</button>' : '') +
      (isLeader ? '<button class="seg-btn" data-tact="transfer" data-tid="' + tid + '">转让队长</button>' : '') +
      '<button class="seg-btn" data-tact="leave" data-tid="' + tid + '">退出这个队伍</button>' +
      (isLeader ? '<button class="seg-btn" data-tact="disband" data-tid="' + tid + '">解散</button>' : '') +
      '</div>';
    return '<div class="card">' + head + body + switches + appsBlock + acts + '</div>';
  }

  function renderTeam(d) {
    // 一个人可能在好几个队里：先看 myTeams，老服务端只有 team 就退回单个
    var mine = d.myTeams || [];
    if (!mine.length && d.team) mine = [d.team];
    state.myTeams = mine;
    if (!state.chatTeam && mine.length) state.chatTeam = mine[0].id;
    renderTeamView();
    if (state.pendingChannel === 'team' && mine.length) {
      state.pendingChannel = '';
      state.chatChannel = 'team';
      state.chatLast = 0;
      state.chatMsgs = [];
      renderChatChannels();
      loadChat();
    }
    if (state.chatTeam && !mine.some(function (t) { return t.id === state.chatTeam; })) {
      state.chatTeam = mine.length ? mine[0].id : '';
    }
    renderChatChannels();

    if (mine.length) {
      $('team-mine').classList.remove('hidden');
      $('team-none').classList.add('hidden');
      $('team-name').textContent = mine.length + ' 个队伍';
      $('team-sub').textContent = mine.map(function (t) {
        return plain(t.coloredName || t.name);
      }).join(' / ');
      $('team-members').innerHTML = mine.map(teamCard).join('');
      $('team-actions').innerHTML = '';
    } else {
      $('team-mine').classList.add('hidden');
      $('team-none').classList.remove('hidden');
    }

    var list = d.teams || [];
    $('team-count').textContent = list.length ? list.length + ' 个' : '—';
    $('team-list').innerHTML = list.length ? list.map(function (t) {
      var joined = mine.some(function (x) { return x.name === t.name; });
      return '<div class="list-item"><div class="main"><b>' + esc(plain(t.coloredName || t.name)) + '</b>' +
        '<div class="sub">队长 ' + esc(t.leaderName || '—') + ' · ' + (t.size || 0) + '/' + (t.maxSize || 0) + ' 人</div>' +
        '<div class="tags"><span class="tag">标识 ' + esc(plain(t.tag || '—')) + '</span>' +
        (t.publicJoin === false ? '<span class="tag">需申请</span>' : '') + '</div></div>' +
        (joined ? '<span class="tag on">我的队伍</span>'
          : (t.publicJoin === false
            ? '<button class="btn btn-mini" data-apply="' + esc(t.id || '') + '" data-tname="' + esc(t.name) + '">申请加入</button>'
            : '<button class="btn btn-mini" data-join="' + esc(t.name) + '">加入</button>')) +
        '</div>';
    }).join('') : '<p class="empty">服务器上还没有队伍</p>';
  }

  function teamDecide(teamId, player, accept) {
    api('/api/team', { body: { action: 'decide', team: teamId, arg: player, accept: accept ? '1' : '0' } })
      .then(function (r) {
        var d = r.data || {};
        toast(d.message || (d.ok ? '完成' : '失败'));
        loadTeam();
      }).catch(function (e) { toast(e.message); });
  }

  function teamAction(action, arg, teamId) {
    api('/api/team', { body: { action: action, arg: arg || '', team: teamId || '' } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || (d.ok ? '完成' : '失败'));
      loadTeam();
      loadMe();
    }).catch(function (e) { toast(e.message); });
  }

  // ── 头衔 ─────────────────────────────────────────────

  function loadTitles() {
    api('/api/titles').then(function (r) {
      var d = r.data || {};
      if (!d.ok) { toast(d.message || '读不到头衔'); return; }
      renderTitles(d.data || {});
    }).catch(function (e) { fatal('读取头衔失败', e.message); });
  }

  var STAT_NAME = { mining: '挖矿', farming: '收获', deaths: '死亡', fishing: '钓鱼', kills: '击杀', mobkills: '杀怪' };

  function condText(t) {
    var v = t.conditionValue || 0;
    var ct = t.conditionType || '';
    if (t.permission) return '需要权限 ' + t.permission;
    if (ct === 'playtime') return '累计在线 ' + Math.round(v / 3600000) + ' 小时';
    if (ct === 'daily_playtime') return '今日在线 ' + Math.round(v / 3600000) + ' 小时';
    if (ct === 'statistic') return (STAT_NAME[t.statisticType] || t.statisticType || '统计') + ' ' + fmt(v) + ' 次';
    if (ct === 'festival') return '节日活动';
    if (ct === 'manual') return '管理员发放';
    return ct || '—';
  }

  function renderTitles(data) {
    var titles = data.titles || [];
    var activeTitle = data.activeTitle;
    var active = titles.filter(function (t) { return t.active; })[0];

    if (active || activeTitle) {
      $('title-active').textContent = active ? active.display : plain(activeTitle);
      $('title-active-sub').textContent = active ? (active.prefix || '').replace(/§./g, '') : '';
      $('btn-title-off').classList.remove('hidden');
    } else {
      $('title-active').textContent = '还没戴头衔';
      $('title-active-sub').textContent = '下面挑一个戴上';
      $('btn-title-off').classList.add('hidden');
    }

    $('title-count').textContent = (data.unlockedCount || 0) + ' / ' + (data.total || titles.length);

    $('title-list').innerHTML = titles.length ? titles.map(function (t) {
      var tag = t.active ? '<span class="tag on">佩戴中</span>'
        : (t.unlocked ? '<span class="tag ok">已解锁</span>' : '<span class="tag">未解锁</span>');
      var btn = t.active ? '' :
        '<button class="btn btn-mini" data-title="' + esc(t.id) + '">' + (t.unlocked ? '戴上' : '查看') + '</button>';
      var prog = '';
      if (!t.unlocked && t.progress >= 0 && t.conditionValue > 0) {
        var pct = Math.min(100, Math.round(t.progress * 100 / t.conditionValue));
        prog = '<div class="exp-bar" style="margin:6px 0 0;height:6px"><i style="width:' + pct + '%"></i></div>' +
          '<div class="hint">进度 ' + pct + '%</div>';
      }
      return '<div class="list-item"><div class="main"><b>' + esc(t.display || t.id) + '</b>' +
        '<div class="sub">' + esc(condText(t)) + '</div>' + prog + '</div>' +
        '<div class="tags">' + tag + '</div>' + btn + '</div>';
    }).join('') : '<p class="empty">服务器上没有配头衔</p>';
  }

  function equipTitle(id) {
    api('/api/titles', { body: { id: id || '' } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || (d.ok ? '完成' : '失败'));
      if (d.data) renderTitles(d.data);
      loadMe();
    }).catch(function (e) { toast(e.message); });
  }

  // ── 命令 ─────────────────────────────────────────────

  function loadCommands() {
    if (state.cmdCatalog) { renderCmdGroups(); return; }
    api('/api/admin/commands').then(function (r) {
      var d = r.data || {};
      if (!d.ok) { $('cmd-list').innerHTML = '<p class="empty">' + esc(d.message || '读不到命令表') + '</p>'; return; }
      state.cmdCatalog = d;
      renderCmdGroups();
    }).catch(function (e) { fatal('读取命令表失败', e.message); });
  }

  function renderCmdGroups() {
    var cat = state.cmdCatalog || {};
    var groups = cat.groups || [];
    if (!groups.length) {
      $('cmd-groups').innerHTML = '';
      $('cmd-list').innerHTML = '<p class="empty">服务器没给命令表（admin.commands.enabled 关着？）</p>';
      return;
    }
    if (!state.cmdGroup || !groups.some(function (g) { return g.name === state.cmdGroup; })) {
      state.cmdGroup = groups[0].name;
    }
    $('cmd-groups').innerHTML = groups.map(function (g) {
      return '<button class="seg-btn' + (g.name === state.cmdGroup ? ' active' : '') +
        '" data-group="' + esc(g.name) + '">' + esc(g.name) + '</button>';
    }).join('');
    var cur = groups.filter(function (g) { return g.name === state.cmdGroup; })[0];
    var items = (cur && cur.items) || [];
    $('cmd-list').innerHTML = items.map(function (it) {
      return '<button class="cmd-item' + (it.danger ? ' danger' : '') + '" data-cmd="' + esc(it.cmd) + '">' +
        '<div class="cmd-label">' + esc(it.label) + (it.danger ? '<span class="cmd-badge">谨慎</span>' : '') + '</div>' +
        '<div class="cmd-args">' + esc(it.cmd) + (it.args ? ' ' + esc(it.args) : '') + '</div>' +
        '<div class="cmd-desc">' + esc(it.desc || '') + '</div></button>';
    }).join('');
  }

  function runCommand() {
    var cmd = $('f-cmd').value.trim();
    if (!cmd) { toast('先写要执行的命令'); return; }
    var dangerous = /^(stop|restart|reload|op|deop|whitelist|give|gamemode|gm|kick)\b/i.test(cmd);
    if (dangerous && !confirm('「' + cmd + '」可能弄坏服务器，确定要执行？')) return;

    var btn = $('btn-run');
    btn.disabled = true;
    btn.textContent = '执行中…';
    api('/api/admin/exec', { body: { command: cmd } }).then(function (r) {
      var d = r.data || {};
      showCmdOutput('$ ' + cmd, d.output || [], d.ok, d.message);
    }).catch(function (e) {
      showCmdOutput('$ ' + cmd, [], false, e.message);
    }).then(function () {
      btn.disabled = false;
      btn.textContent = '执行';
    });
  }

  function showCmdOutput(echo, lines, ok, message) {
    var box = $('cmd-out');
    var html = '<div class="cmd-echo">' + esc(echo) + '</div>';
    if (message) html += '<div class="' + (ok ? '' : 'cmd-err') + '">' + esc(message) + '</div>';
    if (lines && lines.length) {
      html += lines.map(function (l) { return '<div>' + esc(l) + '</div>'; }).join('');
    } else if (ok) {
      html += '<div class="hint">（没有输出）</div>';
    }
    box.innerHTML = html;
    box.classList.remove('hidden');
  }

  // ── 更多：在线玩家 / 管理 / 设置 ─────────────────────

  var STAFF_ROLES = [
    { key: 'owner', label: '服主', ico: '👑' },
    { key: 'coowner', label: '副服主', ico: '🎖' },
    { key: 'admin', label: '管理员', ico: '🛡' },
    { key: 'moderator', label: '协管', ico: '🔧' }
  ];

  function loadStaff() {
    if (Date.now() - (state.listTapAt || 0) < 1000) return;
    api('/api/admin/staff').then(function (r) {
      var d = r.data || {};
      var list = d.staff || [];
      var box = $('staff-list');
      if (!box) return;
      if (!list.length) { box.innerHTML = '<p class="empty">还没有名单</p>'; return; }
      var online = list.filter(function (p) { return p.online; }).length;
      $('staff-sum').textContent = list.length + ' 人 · ' + online + ' 在线';
      box.innerHTML = STAFF_ROLES.map(function (role) {
        var group = list.filter(function (p) { return p.role === role.key; });
        if (!group.length) return '';
        return '<div class="staff-group"><div class="staff-head">' + role.ico + ' ' + role.label +
          '<span class="side">' + group.length + ' 人</span></div>' +
          group.map(function (p) {
            return '<div class="list-item" data-manage="' + esc(p.name) + '"><i class="dot' + (p.online ? ' on' : '') + '"></i>' +
              '<div class="main"><b>' + esc(p.name) + '</b>' +
              '<div class="sub">' + (p.online ? '在线' : '离线') +
              (p.identity && p.identity.label ? ' · ' + esc(p.identity.label) : '') + '</div></div>' +
              '<button class="btn btn-mini" data-manage="' + esc(p.name) + '">详情</button></div>';
          }).join('') + '</div>';
      }).join('');
    }).catch(function (e) {
      var box = $('staff-list');
      if (box) box.innerHTML = '<p class="empty">' + esc(e.message) + '</p>';
    });
  }

  function loadPlayers() {
    loadStaff();
    if (Date.now() - (state.listTapAt || 0) < 1000) return;
    api('/api/players').then(function (r) {
      var d = r.data || {};
      var list = d.players || [];
      $('online-list').innerHTML = list.length ? list.map(function (p) {
        var tags = [];
        if (p.team) tags.push('<span class="tag">' + esc(plain(p.team)) + '</span>');
        if (p.titleId) tags.push('<span class="tag ok">' + esc(p.titleId) + '</span>');
        return '<div class="list-item"' + (state.admin ? ' data-manage="' + esc(p.name) + '"' : '') + '><i class="dot on"></i><div class="main"><b>' + esc(p.name) + '</b>' +
          '<div class="sub">' + (p.level == null ? '—' : p.level) + ' 级 · ' + (p.ping == null ? '—' : p.ping + 'ms') +
          ' · ' + esc(p.world || '—') + '</div><div class="tags">' + tags.join('') + '</div></div>' +
          (state.admin ? '<button class="btn btn-mini" data-manage="' + esc(p.name) + '">管理</button>' : '') +
          '</div>';
      }).join('') : '<p class="empty">现在没人在线</p>';
    }).catch(function (e) { $('online-list').innerHTML = '<p class="empty">' + esc(e.message) + '</p>'; });
  }

  function loadStatus() {
    api('/api/admin/status').then(function (r) {
      var d = r.data || {};
      if (!d.ok) return;
      // 只显示 Linux 系插件；别的插件的信息不进 App
      // Linux 系 + 假人插件都属于自家插件，一起显示
      var plugins = (d.plugins || []).filter(function (p) {
        return p && p.name && (p.name.indexOf('Linux') === 0 || p.name.indexOf('FakePlayer') === 0 ||
          p.name.indexOf('InTheNameOfLinux') === 0);
      });
      var on = plugins.filter(function (p) { return p.loaded; }).length;
      $('status-sub').textContent = on + ' / ' + plugins.length + ' 已加载';
      $('status-list').innerHTML = plugins.map(function (p) {
        return '<div class="list-item"><div class="main"><b>' + esc(p.label || p.name) + '</b>' +
          '<div class="sub">' + esc(p.name) + (p.version ? ' · v' + esc(p.version) : '') + '</div></div>' +
          '<span class="tag ' + (p.loaded ? 'ok' : '') + '">' + (p.loaded ? '已加载' : '没装') + '</span></div>';
      }).join('');

      var hooks = d.hooks || {};
      var feats = d.features || {};
      var bits = [];
      Object.keys(hooks).forEach(function (k) {
        if (hooks[k]) bits.push(k);
      });
      if (bits.length) {
        $('status-list').innerHTML += '<p class="hint" style="margin-top:10px">已接上的能力：' + esc(bits.join(' / ')) + '</p>';
      }
      var off = Object.keys(feats).filter(function (k) { return !feats[k]; });
      if (off.length) {
        $('status-list').innerHTML += '<p class="hint">关掉的功能：' + esc(off.join(' / ')) + '</p>';
      }
    }).catch(function () {});
  }

  var ADMIN_TABS = ['online', 'player', 'report', 'penalty', 'fake', 'cmd', 'su'];

  function setAdminTab(tab) {
    if (typeof syncSegs === "function") setTimeout(function () { syncSegs(true); }, 30);

    if (tab === 'su' && !isSuOn()) tab = 'online';
    if (tab === 'online') loadPlayers();
    if (tab === 'su') renderSuConsole();
    if (ADMIN_TABS.indexOf(tab) < 0) tab = 'player';
    state.adminTab = tab;
    Array.prototype.forEach.call(document.querySelectorAll('#admin-tabs .seg-btn'), function (b) {
      b.classList.toggle('active', b.getAttribute('data-admin') === tab);
    });
    ADMIN_TABS.forEach(function (t) {
      var el = $('admin-' + t);
      if (el) el.classList.toggle('hidden', t !== tab);
    });
    if (tab === 'report') loadReports();
    if (tab === 'penalty') loadPenalties();
    if (tab === 'fake') loadFakes();
    if (tab === 'cmd') loadCommands();
  }

  function searchPlayers() {
    var q = $('f-search').value.trim();
    if (!q) { toast('先写个名字'); return; }
    $('search-list').innerHTML = '<p class="empty">搜索中…</p>';
    $('player-detail').classList.add('hidden');
    api('/api/admin/players?q=' + encodeURIComponent(q)).then(function (r) {
      var d = r.data || {};
      var list = d.players || [];
      $('search-list').innerHTML = list.length ? list.map(function (p) {
        return '<div class="list-item"><div class="main"><b>' + esc(p.name) + '</b>' +
          '<div class="sub">' + (p.online ? '在线' : '离线') + (p.banned ? ' · 已封禁' : '') + '</div></div>' +
          '<button class="btn btn-mini" data-detail="' + esc(p.name) + '">详情</button></div>';
      }).join('') : '<p class="empty">没找到这个人</p>';
    }).catch(function (e) { $('search-list').innerHTML = '<p class="empty">' + esc(e.message) + '</p>'; });
  }

  function playerDetail(name, silent) {
    if (!silent) state.detailFrom = state.adminTab || 'online';
    state.detailName = name;
    var seq = (state.detailSeq = (state.detailSeq || 0) + 1);
    api('/api/admin/player?name=' + encodeURIComponent(name)).then(function (r) {
      if (seq !== state.detailSeq) return;   // 已经返回列表了，别再把用户拽回详情页
      var d = r.data || {};
      if (!d.ok) { toast(d.message || '读不到'); return; }
      var p = d.data || {};
      var rows = [
        ['状态', p.online ? '在线' : '离线'],
        ['金币', p.hasCoins ? fmtAmount(p.coins) : '—'],
        ['点券', p.hasPoints ? fmtAmount(p.points) : '—'],
        ['等级', p.level == null ? '—' : p.level],
        ['在线时长', hoursText(p.playtimeHours)],
        ['身份', (p.identity && p.identity.label) || p.role || '无'],
        ['队伍', p.team ? plain(p.team) : '无'],
        ['头衔', p.titleName || '无'],
        ['封禁', p.banned ? ('是 · ' + (p.banReason || '未填写')) : '否']
      ].map(function (x) {
        return '<span>' + esc(x[0]) + '</span><b>' + esc(x[1]) + '</b>';
      }).join('');

      var penalties = [];
      var pmap = d.penalties || {};
      Object.keys(pmap).forEach(function (k) {
        if (pmap[k]) penalties.push(k);
      });

      var html = '<div class="card" style="margin:0"><div class="card-title"><h2>' + esc(p.name) + '</h2>' +
        '<span class="side">' + (penalties.length ? '在惩罚中：' + esc(penalties.join(' / ')) : '没有惩罚') + '</span></div>' +
        '<div class="kv">' + rows + '</div></div>';

      var caps = state.caps || {};
      var kinds = [];
      if (caps.mute === true) kinds.push(['banmsg', '禁言']);
      if (caps.ban === true) {
        kinds.push(['banmove', '禁止移动'], ['banblock', '禁止交互'], ['all', '全部禁止'], ['ban', '封禁']);
      }
      var noPenaltyPerm = !kinds.length;
      if (!kinds.length) kinds = [];
      html += '<div class="hint" style="margin-top:12px">' +
        (noPenaltyPerm ? '你没有处罚权限（需要 inthenameoflinux.penalty / .ban），这里只能看。'
          : (state.moderatorOnly ? '惩罚（分钟数必填，1 ~ 43200，最多 30 天）' : '惩罚（分钟数留空 = 永久）')) + '</div>' +
        '<div class="seg" style="margin-top:6px">' +
        kinds.map(function (k) {
          return '<button class="seg-btn" data-pen="' + k[0] + '">' + k[1] + '</button>';
        }).join('') +
        '</div>' +
        '<div class="seg" style="margin-top:8px">' +
        kinds.map(function (k) {
          var short = { banmsg: '解除禁言', banmove: '解除移动', banblock: '解除交互', all: '解除全部', ban: '解除封禁' };
          return '<button class="seg-btn" data-unpen="' + k[0] + '">' + (short[k[0]] || ('解除' + k[1])) + '</button>';
        }).join('') +
        '</div>' +
        (penalties.length ? '<div class="hint" style="margin-top:8px">生效中：' + esc(penalties.join(' / ')) + '</div>' : '');

      var reps = d.reports || [];
      if (reps.length) {
        html += '<div class="hint" style="margin-top:10px">最近被举报：</div>' + reps.slice(0, 5).map(function (x) {
          return '<div class="list-item"><div class="main"><b>#' + x.id + ' ' + esc(x.reason) + '</b>' +
            '<div class="sub">' + esc(x.reporter) + ' · ' + (x.handled ? '已处理' : '待处理') + '</div></div></div>';
        }).join('');
      }
      // 详情独立成页：搜完点「详情」直接跳过去，不再挤在管理页里
      var box = $('pdetail-body');
      box.innerHTML = html;
      $('page-sub').textContent = name;
      if (!silent) switchPage('pdetail');   // 静默刷新（refreshPage）时不要再跳一次
    }).catch(function (e) { toast(e.message); });
  }

  var MODERATOR_MAX_MINUTES = 30 * 24 * 60;   // 协管的处罚上限：30 天

  function applyPenalty(type, name, minutes, reason) {
    if (state.moderatorOnly) {
      var mins = Number(minutes || 0);
      if (!mins || mins <= 0) { toast('请填写处罚时长（1 ~ 43200 分钟），协管不能永久处罚'); return; }
      if (mins > MODERATOR_MAX_MINUTES) { toast('协管最多只能处罚 30 天，要更久请让管理员操作'); return; }
    }
    api('/api/admin/penalty', {
      body: { op: 'apply', type: type, name: name, minutes: minutes || 0, reason: reason || '' }
    }).then(function (r) {
      var d = r.data || {};
      toast(d.message || (d.ok ? '已执行' : '失败'));
      if (d.ok) { loadPenalties(); playerDetail(name, true); }
    }).catch(function (e) { toast(e.message); });
  }

  function releasePenalty(type, name) {
    api('/api/admin/penalty', { body: { op: 'release', type: type, name: name } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || '已解除');
      loadPenalties();
      if (name) playerDetail(name, true);
    }).catch(function (e) { toast(e.message); });
  }

  function fakeToggle(name, key, on) {
    api('/api/admin/fake', { body: { op: 'toggle', name: name, key: key, on: String(on) } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || (d.ok ? '已设置' : '失败'));
      loadFakes();
    }).catch(function (e) { toast(e.message); });
  }

  function loadReports() {
    var filter = state.reportFilter || 'all';
    $('report-list').innerHTML = '<p class="empty">加载中…</p>';
    api('/api/admin/reports?filter=' + filter).then(function (r) {
      var d = r.data || {};
      var list = d.reports || [];
      var handledN = d.handledCount || 0;
      var pendingN = (d.count || 0) - handledN;
      $('report-count').textContent = (d.count || 0)
        ? ((d.count || 0) + ' 条 · 待处理 ' + pendingN + ' · 已处理 ' + handledN)
        : '还没有举报记录';
      renderReportTabs(filter);
      $('report-list').innerHTML = list.length ? list.map(function (x) {
        var t = new Date(x.time || Date.now());
        var done = !!x.handled;
        var head = '<div class="sub">举报人 ' + esc(x.reporter) + ' · ' + pad(t.getMonth() + 1) + '-' +
          pad(t.getDate()) + ' ' + pad(t.getHours()) + ':' + pad(t.getMinutes()) + '</div>';
        var tail = done
          ? ('<div class="sub">处理：' + esc(x.result === 'reject' ? '驳回' : '受理') +
             (x.handler ? (' · ' + esc(x.handler)) : '') + (x.note ? (' · ' + esc(plain(x.note))) : '') + '</div>')
          : ('<div class="seg" style="margin-top:8px">' +
             '<button class="seg-btn" data-rep="accept" data-id="' + x.id + '">受理</button>' +
             '<button class="seg-btn" data-rep="reject" data-id="' + x.id + '">驳回</button>' +
             '</div>');
        return '<div class="list-item" style="align-items:flex-start"><div class="main">' +
          '<b>#' + x.id + ' ' + esc(x.target) + (done ? ' <span class="tag">已处理</span>' : '') + '</b>' +
          '<div class="sub">' + esc(plain(x.reason || '')) + '</div>' + head + tail +
          '</div></div>';
      }).join('') : '<p class="empty">' +
        (filter === 'handled' ? '还没有已处理的举报' : (filter === 'pending' ? '没有待处理的举报' : '还没有举报记录')) +
        '</p>';
    }).catch(function (e) { $('report-list').innerHTML = '<p class="empty">' + esc(e.message) + '</p>'; });
  }

  function renderReportTabs(filter) {
    var box = $('report-tabs');
    if (!box) return;
    Array.prototype.forEach.call(box.querySelectorAll('.seg-btn'), function (b) {
      b.classList.toggle('active', (b.getAttribute('data-report-tab') || 'all') === filter);
    });
  }

  function handleReport(op, id) {
    var note = op === 'accept' ? prompt('处理说明（可留空）', '已处理') : prompt('驳回原因（可留空）', '证据不足');
    if (note === null) return;
    api('/api/admin/report', { body: { op: op, id: id, note: note } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || (d.ok ? '已处理' : '失败'));
      loadReports();
    }).catch(function (e) { toast(e.message); });
  }

  function loadPenalties() {
    $('penalty-list').innerHTML = '<p class="empty">加载中…</p>';
    api('/api/admin/penalties').then(function (r) {
      var d = r.data || {};
      var list = d.penalties || [];
      $('penalty-count').textContent = list.length ? list.length + ' 条' : '没有生效中的惩罚';
      var CN = { banmsg: '禁言', banmove: '禁移动', banblock: '禁破坏', banall: '全部惩罚' };
      $('penalty-list').innerHTML = list.length ? list.map(function (x) {
        return '<div class="list-item"><div class="main"><b>' + esc(x.player) + '</b>' +
          '<div class="sub">' + esc(CN[x.type] || x.type) + ' · ' + esc(plain(x.reason || '')) + '</div>' +
          '<div class="sub">到期 ' + esc(x.expire || '—') + ' · 执行人 ' + esc(x.operator || '—') + '</div></div>' +
          '<button class="btn btn-mini" data-release="' + esc(x.type) + '" data-who="' + esc(x.player) + '">解除</button></div>';
      }).join('') : '<p class="empty">现在没有生效中的惩罚</p>';
    }).catch(function (e) { $('penalty-list').innerHTML = '<p class="empty">' + esc(e.message) + '</p>'; });
  }

  var fakeSettings = {};

  function renderFakeSettings(st) {
    fakeSettings = st || {};
    var gm = fakeSettings.gamemode || 'CREATIVE';
    var inv = !!fakeSettings.invulnerable;
    $('fake-gm-text').textContent = gm === 'CREATIVE' ? '创造' : (gm === 'SURVIVAL' ? '生存'
      : (gm === 'ADVENTURE' ? '冒险' : gm));
    $('fake-inv-text').textContent = inv ? '是' : '否';
    $('fake-settings-sub').textContent = fakeSettings.prefix
      ? ('名字前缀 ' + fakeSettings.prefix) : '—';
  }

  function fakeSet(key, value) {
    api('/api/admin/fake', { body: { op: 'set', key: key, value: value } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || '已保存');
      if (d.ok) loadFakes();
    }).catch(function (e) { toast(e.message); });
  }

  function fakeRespawn(name) {
    api('/api/admin/fake', { body: { op: 'respawn', name: name } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || '已重生');
      loadFakes();
    }).catch(function (e) { toast(e.message); });
  }

  function loadFakes() {
    $('fake-list').innerHTML = '<p class="empty">加载中…</p>';
    api('/api/admin/fakes').then(function (r) {
      var d = r.data || {};
      if (!d.available) {
        $('fake-list').innerHTML = '<p class="empty">服务器没装 FakePlayerPlugin</p>';
        return;
      }
      renderFakeSettings(d.settings || {});
      var list = d.fakes || [];
      $('fake-list').innerHTML = list.length ? list.map(function (f) {
        var who = f.creator ? (' · 召唤者 ' + esc(f.creator)) : '';
        var enabled = f.enabled !== false;
        var loaded = f.loaded !== false;
        var spawn = f.mobSpawning !== false;
        var tags = '<span class="tag ' + (enabled ? 'ok' : 'off') + '">' + (enabled ? '已启用' : '已停用') + '</span>';
        if (enabled) {
          tags += '<span class="tag ' + (loaded ? 'ok' : '') + '">' + (loaded ? '区块已加载' : '区块没加载') + '</span>' +
            '<span class="tag ' + (spawn ? 'ok' : '') + '">' + (spawn ? '会刷怪' : '不刷怪') + '</span>';
        } else {
          tags += '<span class="tag off">不在服务器里，记录还留着</span>';
        }
        var btns = '<div class="seg" style="margin-top:8px">' +
          '<button class="seg-btn" data-fake-toggle="enabled" data-on="' + (enabled ? '0' : '1') + '" data-name="' +
          esc(f.name) + '">' + (enabled ? '停用假人' : '启用假人') + '</button>';
        if (enabled) {
          btns += '<button class="seg-btn" data-fake-toggle="load" data-on="' + (loaded ? '0' : '1') + '" data-name="' +
            esc(f.name) + '">' + (loaded ? '停载区块' : '加载区块') + '</button>' +
            '<button class="seg-btn" data-fake-toggle="spawn" data-on="' + (spawn ? '0' : '1') + '" data-name="' +
            esc(f.name) + '">' + (spawn ? '关刷怪' : '开刷怪') + '</button>' +
            '<button class="seg-btn" data-fake-respawn="' + esc(f.name) + '">重生</button>';
        }
        btns += '<button class="seg-btn" data-fake-remove="' + esc(f.name) + '">移除</button></div>';
        return '<div class="list-item" style="align-items:flex-start"><i class="dot' + (f.active ? ' on' : '') + '"></i>' +
          '<div class="main"><b>' + esc(f.name) + '</b>' +
          '<div class="sub">' + esc(f.world || '—') + ' · ' + Math.round(f.x) + ', ' + Math.round(f.y) + ', ' +
          Math.round(f.z) + who + '</div>' +
          '<div class="tags" style="margin-top:6px">' + tags + '</div>' + btns +
          '</div></div>';
      }).join('') : '<p class="empty">还没有假人</p>';
    }).catch(function (e) { $('fake-list').innerHTML = '<p class="empty">' + esc(e.message) + '</p>'; });
  }

  function fakeAdd() {
    var name = $('f-fake').value.trim();
    if (!name) { toast('写个假人名字'); return; }
    api('/api/admin/fake', { body: { op: 'add', name: name } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || (d.ok ? '已召唤' : '失败'));
      if (d.ok) { $('f-fake').value = ''; loadFakes(); }
    }).catch(function (e) { toast(e.message); });
  }

  function fakeRemove(name) {
    api('/api/admin/fake', { body: { op: 'remove', name: name } }).then(function (r) {
      var d = r.data || {};
      toast(d.message || '已移除');
      loadFakes();
    }).catch(function (e) { toast(e.message); });
  }

  // ── 接口地址 ─────────────────────────────────────────

  function saveServer() {
    state.server = normServer($('f-server-edit').value);
    $('f-server-edit').value = state.server;
    persist();
    $('set-server').textContent = state.server.replace(/^https?:\/\//, '');
    $('server-msg').textContent = '已保存：' + state.server + '，正在重新拉数据…';
    loadMe();
    loadPlayers();
  }

  function testServer() {
    var url = normServer($('f-server-edit').value);
    $('server-msg').textContent = '正在连 ' + url + ' …';
    fetch(url + '/api/health', { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        var caps = d.capabilities || {};
        var on = Object.keys(caps).filter(function (k) { return caps[k]; });
        $('server-msg').textContent = d.ok
          ? ('通了：' + (d.app || 'LinuxAPP') + ' v' + (d.version || '?') +
            '（' + (on.length ? on.join('、') + ' ✓' : '没接上任何插件') + '）')
          : '连上了，但服务器说：' + (d.message || '不正常');
      })
      .catch(function (e) {
        $('server-msg').textContent = '连不上 ' + url + ' —— ' + e.message;
      });
  }

  function resetServer() {
    state.server = normServer(DEFAULT_SERVER);
    $('f-server-edit').value = state.server;
    persist();
    $('set-server').textContent = state.server.replace(/^https?:\/\//, '');
    $('server-msg').textContent = '已恢复默认：' + state.server;
    loadMe();
  }

  // ── 定时刷新 ─────────────────────────────────────────

  function startTimers() {
    stopTimers();
    state.timer = setInterval(function () {
      if (state.page === 'me') { loadMe(); loadMyReports(); }
    }, 30000);
    // 聊天每 2 秒查一次（就是「实时」）；不在聊天页时每 5 拍（10 秒）查一次，只为点亮红点
    state.chatTick = 0;
    state.chatTimer = setInterval(function () {
      // 上一次请求卡死（弱网、切后台）时把它放掉，否则轮询会一直空转不再发请求
      if (state.chatLoading && Date.now() - (state.chatFetchAt || 0) > 8000) {
        state.chatLoading = false;
        state.chatPending = false;
      }
      state.chatTick = (state.chatTick || 0) + 1;
      if (state.page === 'chat') { loadChat(); return; }
      if (state.chatTick % 2 !== 0) return;
      if (state.chatLoading) return;
      api('/api/chat?since=' + (state.chatLast || 0)).then(function (r) {
        var d = r.data || {};
        if (d.reset) { state.chatMsgs = []; state.chatLast = 0; resetNoticeWatermark(); }
        var msgs = d.messages || [];
        if (!msgs.length) return;
        var seen = {};
        (state.chatMsgs || []).forEach(function (m) { if (m && m.id) seen[m.id] = 1; });
        var fresh = msgs.filter(function (m) { return m && m.id && !seen[m.id]; });
        if (fresh.length) state.chatMsgs = state.chatMsgs.concat(fresh).slice(-200);
        if ((d.last || 0) > (state.chatLast || 0)) state.chatLast = d.last;
        $('chat-dot').classList.remove('hidden');
        // 不在聊天页：只对「新到」的消息弹一条
        if (!state.suppressPop) notifyNewest(fresh);
      }).catch(function () {});
    }, 2000);
  }

  function stopTimers() {
    if (state.timer) clearInterval(state.timer);
    if (state.chatTimer) clearInterval(state.chatTimer);
    if (state.chatSlowTimer) clearInterval(state.chatSlowTimer);
    state.timer = 0;
    state.chatTimer = 0;
    state.chatSlowTimer = 0;
  }

  // ── 事件绑定 ─────────────────────────────────────────

  function bind() {
    $('btn-login').addEventListener('click', doLogin);
    if ($('btn-diag')) $('btn-diag').addEventListener('click', diagServer);
    $('f-pass').addEventListener('keydown', function (e) { if (e.key === 'Enter') doLogin(); });
    $('btn-refresh').addEventListener('click', function () {
      refreshPage();
      toast('已刷新');
    });

    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (b) {
      b.addEventListener('click', function () { switchPage(b.getAttribute('data-page')); });
    });

    $('btn-checkin').addEventListener('click', doCheckin);
    $('btn-linux').addEventListener('click', openLinux);
    $('btn-linux-close').addEventListener('click', closeLinux);
    $('btn-linux-cfg-save').addEventListener('click', saveLinuxConfig);
    $('btn-linux-cfg-close').addEventListener('click', function () {
      $('linux-cfg-modal').classList.add('hidden');
      openLinux();
    });
    $('btn-chat-send').addEventListener('click', sendChat);
    $('btn-transfer').addEventListener('click', openTransfer);
    $('btn-transfer-go').addEventListener('click', doTransfer);
    $('btn-transfer-close').addEventListener('click', closeTransfer);
    [].slice.call(document.querySelectorAll('[data-ttab]')).forEach(function (b) {
      b.addEventListener('click', function () { setTransferTab(b.getAttribute('data-ttab')); });
    });
    $('f-chat').addEventListener('keydown', function (e) { if (e.key === 'Enter') sendChat(); });
    $('btn-team-create').addEventListener('click', function () {
      var name = $('f-team-name').value.trim();
      if (!name) { toast('先写队伍名字'); return; }
      teamAction('create', name);
    });
    $('btn-team-leave').addEventListener('click', function () {
      if (confirm('确定退出当前队伍？')) teamAction('leave', '');
    });
    $('btn-title-off').addEventListener('click', function () { equipTitle(''); });
    $('btn-report').addEventListener('click', submitReport);
    $('btn-pdetail-back').addEventListener('click', function () {
      state.detailSeq = (state.detailSeq || 0) + 1;   // 作废还没回来的详情请求
      var back = state.detailFrom || state.lastAdminTab || 'online';
      state.adminTab = back;
      state.keepAdminTab = true;                     // 切回管理页时别再被「默认在线」覆盖
      switchPage('admin');
      setAdminTab(back);
    });
    $('btn-su-run').addEventListener('click', runSuConsole);
    $('f-su2').addEventListener('keydown', function (e) { if (e.key === 'Enter') runSuConsole(); });
    $('btn-su-clear').addEventListener('click', suReset);
    $('btn-su-stop-note').addEventListener('click', function () {
      suAppend('能执行：任何 Bukkit / 插件命令（op、give、ltab reload…）。', 'dim-line');
      suAppend('不能执行：stop、restart、end（关服这类永远不给）。', 'dim-line');
    });
    if ($('f-su')) $('f-su').addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter') { ev.preventDefault(); runSu(); }
    });

    document.addEventListener('pointerdown', function (ev) {
      var hit = ev.target && ev.target.closest ? ev.target.closest('#staff-list, #online-list, [data-manage]') : null;
      if (hit) state.listTapAt = Date.now();
    }, true);

    document.addEventListener('click', function (ev) {
      var t = ev.target.closest ? ev.target.closest('[data-linux-op],[data-chat],[data-chatteam],[data-tact],[data-team],[data-leave],[data-join],[data-apply],[data-decide],[data-tset],[data-kick],[data-title],[data-group],[data-cmd],[data-theme-style],[data-accent]') : null;
      if (!t) return;
      if (t.hasAttribute('data-theme-style')) { setSkin(t.getAttribute('data-theme-style')); return; }
      if (t.hasAttribute('data-accent')) { setAccent(t.getAttribute('data-accent')); return; }
      if (t.hasAttribute('data-linux-op')) {
        linuxOp(t.getAttribute('data-linux'), t.getAttribute('data-linux-op'));
        return;
      }
      if (t.hasAttribute('data-chat')) { setChatChannel(t.getAttribute('data-chat')); return; }
      if (t.hasAttribute('data-chatteam')) {
        state.chatTeam = t.getAttribute('data-chatteam');
        state.chatLast = 0;
        state.chatMsgs = [];
        state.chatScrollPending = true;
        renderChatChannels();
        renderChat();
        loadChat();
        return;
      }
      if (t.hasAttribute('data-tact')) {
        var act = t.getAttribute('data-tact');
        var tid = t.getAttribute('data-tid') || '';
        var targ = t.getAttribute('data-targ') || '';
        if (act === 'leave') {
          if (confirm('确定退出这个队伍？')) teamAction('leave', '', tid);
        } else if (act === 'disband') {
          if (confirm('解散之后队伍就没了，确定？')) teamAction('disband', '', tid);
        } else if (act === 'invite') {
          var who = prompt('邀请谁？（要在线）', '');
          if (who) teamAction('invite', who.trim(), tid);
        } else if (act === 'tag') {
          var tag = prompt('新的队标（最多 4 个字）', '');
          if (tag) teamAction('tag', tag.trim(), tid);
        } else if (act === 'transfer') {
          var to = prompt('把队长转给谁？（要在队里）', '');
          if (to) teamAction('transfer', to.trim(), tid);
        } else if (act === 'setting') {
          teamAction('setting', targ, tid);
        }
        return;
      }
      if (t.hasAttribute('data-tset')) {
        var k = t.getAttribute('data-tset');
        var on = t.getAttribute('data-on') === '1';
        if (k === 'land') {
          var landTid = t.getAttribute('data-tid');
          api('/api/team', { body: { action: 'land', team: landTid, on: on ? '0' : '1' } }).then(function (r) {
            var dd = r.data || {};
            toast(dd.message || (dd.ok ? '完成' : '失败'));
            loadTeam();
          }).catch(function (e) { toast(e.message); });
        } else {
          teamAction('setting', k + ':' + (on ? 'off' : 'on'), t.getAttribute('data-tid'));
        }
        return;
      }
      if (t.hasAttribute('data-apply')) {
        teamAction('apply', t.getAttribute('data-apply'), t.getAttribute('data-apply'));
        return;
      }
      if (t.hasAttribute('data-decide')) {
        var dec = t.getAttribute('data-decide');
        teamDecide(t.getAttribute('data-tid'), t.getAttribute('data-name'), dec === 'accept');
        return;
      }
      if (t.hasAttribute('data-leave')) {
        var tn = t.getAttribute('data-leave');
        if (confirm('退出队伍「' + tn + '」？')) teamAction('leave', tn);
      } else if (t.hasAttribute('data-team')) {
        var tname = t.getAttribute('data-tname') || '';
        if (confirm('解散队伍' + (tname ? '「' + tname + '」' : '') + '？这个操作不可撤销')) teamAction('disband', tname);
      } else if (t.hasAttribute('data-join')) {
        teamAction('join', t.getAttribute('data-join'));
      } else if (t.hasAttribute('data-kick')) {
        var who = t.getAttribute('data-kick');
        if (confirm('把 ' + who + ' 踢出队伍？')) teamAction('kick', who);
      } else if (t.hasAttribute('data-title')) {
        var id = t.getAttribute('data-title');
        var row = t.parentNode;
        if (t.textContent === '查看') { toast('这个头衔还没解锁，条件见上面'); return; }
        equipTitle(id);
      } else if (t.hasAttribute('data-group')) {
        state.cmdGroup = t.getAttribute('data-group');
        renderCmdGroups();
      } else if (t.hasAttribute('data-cmd')) {
        var cmd = t.getAttribute('data-cmd');
        $('f-cmd').value = cmd + ' ';
        $('f-cmd').focus();
      }
    });

    $('btn-run').addEventListener('click', runCommand);
    $('f-cmd').addEventListener('keydown', function (e) { if (e.key === 'Enter') runCommand(); });

    $('btn-load-players').addEventListener('click', loadPlayers);
    Array.prototype.forEach.call(document.querySelectorAll('#admin-tabs .seg-btn'), function (b) {
      b.addEventListener('click', function () { setAdminTab(b.getAttribute('data-admin')); });
    });
    $('btn-search').addEventListener('click', searchPlayers);
    $('f-search').addEventListener('keydown', function (e) { if (e.key === 'Enter') searchPlayers(); });
    $('btn-load-reports').addEventListener('click', loadReports);
    $('btn-load-penalties').addEventListener('click', loadPenalties);
    $('btn-fake-add').addEventListener('click', fakeAdd);

    document.addEventListener('click', function (ev) {
      var t = ev.target.closest ? ev.target.closest('[data-manage],[data-detail],[data-rep],[data-release],[data-pen],[data-unpen],[data-ban],[data-fake-remove],[data-fake-respawn],[data-fake-set],[data-fake-toggle],[data-report-tab],[data-tact]') : null;
      if (!t) return;
      if (t.hasAttribute('data-fake-set')) {
        fakeSet(t.getAttribute('data-fake-set'), t.getAttribute('data-value'));
        return;
      }
      if (t.hasAttribute('data-fake-respawn')) { fakeRespawn(t.getAttribute('data-fake-respawn')); return; }
      if (t.hasAttribute('data-report-tab')) {
        state.reportFilter = t.getAttribute('data-report-tab') || 'all';
        loadReports();
        return;
      }
      if (t.hasAttribute('data-fake-toggle')) {
        fakeToggle(t.getAttribute('data-name'), t.getAttribute('data-fake-toggle'), t.getAttribute('data-on'));
        return;
      }
      if (t.hasAttribute('data-unpen')) {
        var w0 = state.detailName || '';
        if (!w0) { toast('先选一个玩家'); return; }
        releasePenalty(t.getAttribute('data-unpen'), w0);
        return;
      }
      if (t.hasAttribute('data-manage')) { playerDetail(t.getAttribute('data-manage')); }
      else if (t.hasAttribute('data-detail')) playerDetail(t.getAttribute('data-detail'));
      else if (t.hasAttribute('data-rep')) handleReport(t.getAttribute('data-rep'), t.getAttribute('data-id'));
      else if (t.hasAttribute('data-release')) releasePenalty(t.getAttribute('data-release'), t.getAttribute('data-who'));
      else if (t.hasAttribute('data-fake-remove')) fakeRemove(t.getAttribute('data-fake-remove'));
      else if (t.hasAttribute('data-pen')) {
        var who = state.detailName || '';
        if (!who) { toast('先选一个玩家'); return; }
        var kind = t.getAttribute('data-pen');
        var label = { banmsg: '禁言', banmove: '禁止移动', banblock: '禁止交互', all: '全部禁止', ban: '封禁' }[kind] || kind;
        var minStr = prompt('给 ' + who + ' 开「' + label + '」多少分钟？（留空 = 永久）', '10');
        if (minStr === null) return;
        var minutes = parseInt(minStr, 10);
        if (isNaN(minutes) || minutes < 0) minutes = 0;
        var why = prompt('原因（可留空）', 'App 管理员操作');
        if (why === null) return;
        applyPenalty(kind, who, minutes, why || '');
      } else if (t.hasAttribute('data-ban')) {
        var target = state.detailName || '';
        if (!target) { toast('先选一个玩家'); return; }
        if (t.getAttribute('data-ban') === '1') {
          var res = prompt('封禁 ' + target + '，原因（留空为未填写）', '违反服务器规定');
          if (res === null) return;
          var mins = prompt('封多久（分钟，0 = 永久）', '0');
          if (mins === null) return;
          applyPenalty('ban', target, parseInt(mins, 10) || 0, res);
        } else {
          releasePenalty('ban', target);
        }
      }
    });

    $('btn-save-server').addEventListener('click', saveServer);
    $('btn-test-server').addEventListener('click', testServer);
    $('btn-reset-server').addEventListener('click', resetServer);
    document.addEventListener('click', function (ev) {
      var seg = ev.target && ev.target.closest ? ev.target.closest('.seg') : null;
      if (seg) setTimeout(function () { placeSegThumb(seg, true); }, 0);
    });
    window.addEventListener('resize', function () { scheduleSegSync(false); });

    if ($('sw-notice-out')) {
      $('sw-notice-out').addEventListener('click', function () { setNotifyOut(!notifyOutOn()); });
    }
    if ($('sw-notify')) {
      $('sw-notify').addEventListener('click', function () { setNotify(!notifyOn()); });
    }
    $('sw-theme').addEventListener('click', toggleTheme);
    if ($('btn-theme')) $('btn-theme').addEventListener('click', openTheme);
    if ($('sw-multi')) $('sw-multi').addEventListener('click', function () {
      setMultiOn(!multiOn());
      renderServersUi();
      if (multiOn()) toast('多服务器已打开，去「软件链接配置」填地址');
    });
    if ($('btn-servers')) $('btn-servers').addEventListener('click', function () {
      renderServerRows();
      switchPage('servers');
    });
    if ($('btn-srv-back')) $('btn-srv-back').addEventListener('click', function () { switchPage('more'); });
    if ($('btn-srv-save')) $('btn-srv-save').addEventListener('click', saveServers);
    if ($('btn-switch')) $('btn-switch').addEventListener('click', openServerSheet);
    if ($('btn-sheet-close')) $('btn-sheet-close').addEventListener('click', closeServerSheet);
    if ($('server-sheet')) $('server-sheet').addEventListener('click', function (ev) {
      if (ev.target === $('server-sheet')) closeServerSheet();
    });
    if ($('sheet-list')) $('sheet-list').addEventListener('click', function (ev) {
      var b = ev.target.closest ? ev.target.closest('.srv-pick') : null;
      if (!b) return;
      closeServerSheet();
      switchServer(Number(b.getAttribute('data-idx')));
    });
    if ($('btn-theme-close')) $('btn-theme-close').addEventListener('click', closeTheme);
    if ($('sw-navfloat')) $('sw-navfloat').addEventListener('click', function () { setNavFloating(!navFloating()); });
    if ($('theme-modal')) $('theme-modal').addEventListener('click', function (ev) {
      if (ev.target === $('theme-modal')) closeTheme();
    });
    if ($('sw-su')) $('sw-su').addEventListener('click', toggleSu);
    $('btn-logout').addEventListener('click', function () { logout(false); });
  }

  // ── 启动 ─────────────────────────────────────────────

  // 顶栏高度算出来给吸顶的聊天频道条用
  function measureTopbar() {
    var bar = document.querySelector('.topbar');
    if (!bar) return;
    var h = Math.round(bar.getBoundingClientRect().height);
    if (h > 0) document.documentElement.style.setProperty('--topbar-h', h + 'px');
  }

  function boot() {
    initTheme();
    bind();
    measureTopbar();
    wrapSegs();
    setTimeout(updateSegArrows, 320);
    // 兜底：两秒半还没收开屏就强制收掉，别让用户盯着看
    setTimeout(hideSplash, 2500);
    window.addEventListener('resize', measureTopbar);
    window.addEventListener('resize', function () { setTimeout(updateSegArrows, 120); });
    window.addEventListener('orientationchange', function () { setTimeout(measureTopbar, 260); });
    // 手机切回前台时立刻同步一次，别等着轮询慢慢补
    document.addEventListener('visibilitychange', function () {
      if (!state.token) return;
      if (document.visibilityState === 'hidden') { bgNotifyStart(); return; }
      bgNotifyStop();
      state.chatLoading = false;
      state.chatPending = false;
      // 回前台补拉消息时不要把几十条都弹一遍
      state.suppressPop = true;
      setTimeout(function () { state.suppressPop = false; }, 1600);
      refreshPage();
    });

    if (multiOn()) {
      var slot = serversLoad()[activeIdx()] || {};
      state.server = normServer(slot.url || localStorage.getItem(LS.server) || DEFAULT_SERVER);
      state.token = slot.token || '';
      // 刚打开多服务器时，主服务器还没存过令牌：沿用当前登录态
      if (!state.token && localStorage.getItem(LS.token)
        && normServer(localStorage.getItem(LS.server) || '') === state.server) {
        state.token = localStorage.getItem(LS.token);
        state.name = localStorage.getItem(LS.name) || '';
        state.role = localStorage.getItem(LS.role) || '';
        slot.token = state.token; slot.player = state.name; slot.role = state.role;
        var _l = serversLoad(); _l[activeIdx()] = slot; serversSave(_l);
      }
      state.name = slot.player || '';
      state.role = slot.role || 'player';
      state.admin = state.role === 'admin' || state.role === 'owner' || state.role === 'su';
    } else {
      state.server = normServer(localStorage.getItem(LS.server) || DEFAULT_SERVER);
      state.token = localStorage.getItem(LS.token) || '';
      state.name = localStorage.getItem(LS.name) || '';
      state.admin = localStorage.getItem(LS.admin) === '1';
    }
    applyRole(state.role || localStorage.getItem(LS.role) || (state.admin ? 'admin' : 'player'));
    $('f-server').value = state.server;
    renderServersUi();

    window.addEventListener('error', function (e) {
      fatal('出错了', (e.message || '') + (e.filename ? '\n' + e.filename + ':' + e.lineno : ''));
    });
    window.addEventListener('unhandledrejection', function (e) {
      fatal('请求出错', (e.reason && e.reason.message) || String(e.reason));
    });

    var segObs = new MutationObserver(function () { scheduleSegSync(true); });
    segObs.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
    setTimeout(function () { syncSegs(false); }, 60);
    setTimeout(function () { syncSegs(false); }, 600);
    setTimeout(function () { placeTabThumb(false); }, 80);
    window.addEventListener('resize', function () { placeTabThumb(false); });

    if (state.token) {
      enterApp();
      var qs = new URLSearchParams(location.search);
      var tab = qs.get('page');
      if (tab) switchPage(tab);
      // 调试用：?chat=team 直接切队伍频道、?tab=cmd 直接开管理页的命令子标签
      var ch = qs.get('chat');
      if (ch) setChatChannel(ch);
      var at = qs.get('tab');
      // su / 命令子标签要等服务端回 su 才认，先记下来，loadMe 之后应用
      if (at && state.admin) state.pendingAdminTab = at;
    }
  }

  /* ── 底栏：高亮块平移（iOS 那种） ─────────────────────────── */
  function placeTabThumb(animate) {
    var tabs = document.querySelector('.tabs');
    if (!tabs) return;
    var active = tabs.querySelector('.tab.active');
    var thumb = tabs.querySelector('.tab-thumb');
    if (!active) { if (thumb) thumb.classList.remove('on'); return; }
    if (!thumb) {
      thumb = document.createElement('i');
      thumb.className = 'tab-thumb';
      thumb.style.transition = 'none';
      tabs.insertBefore(thumb, tabs.firstChild);
      void thumb.offsetWidth;
      thumb.style.transition = '';
    }
    var tr = tabs.getBoundingClientRect();
    var ar = active.getBoundingClientRect();
    thumb.style.width = (active.offsetWidth + 6) + 'px';
    thumb.style.height = (active.offsetHeight + 2) + 'px';
    thumb.style.transform = 'translate(' + (ar.left - tr.left - 3) + 'px,' + (ar.top - tr.top - 1) + 'px)';
    thumb.classList.add('on');
    if (animate === false) {
      thumb.style.transition = 'none';
      void thumb.offsetWidth;
      thumb.style.transition = '';
    }
  }

  /* ── iOS 16/17 式分段控件：白色滑块跟手滑到选中项 ────────────── */
  function segThumbOf(seg) {
    var t = null;
    for (var i = 0; i < seg.children.length; i++) {
      if (seg.children[i].classList && seg.children[i].classList.contains('seg-thumb')) { t = seg.children[i]; break; }
    }
    return t;
  }

  function placeSegThumb(seg, animate) {
    var active = null;
    for (var i = 0; i < seg.children.length; i++) {
      var c = seg.children[i];
      if (c.classList && c.classList.contains('active') && !c.classList.contains('seg-thumb')) { active = c; break; }
    }
    var thumb = segThumbOf(seg);
    if (!active) {
      if (thumb) thumb.parentNode.removeChild(thumb);
      seg.classList.remove('has-thumb');
      return;
    }
    if (getComputedStyle(seg).position === 'static') seg.style.position = 'relative';
    var sr = seg.getBoundingClientRect();
    var ar = active.getBoundingClientRect();
    var box = { x: ar.left - sr.left, y: ar.top - sr.top, w: ar.width, h: ar.height };
    var prev = seg._thumbBox;
    var from = (animate !== false && prev) ? prev : box;
    if (!thumb) {
      thumb = document.createElement('i');
      thumb.className = 'seg-thumb';
      thumb.style.transition = 'none';
      // 上一次的位置还在（比如按钮被整块重绘过）：先停在那里，下一帧再滑过去，
      // 这样「命令分组」「多队伍切换」这种每次重建的控件也有平移效果
      thumb.style.width = from.w + 'px';
      thumb.style.height = from.h + 'px';
      thumb.style.transform = 'translate(' + from.x + 'px,' + from.y + 'px)';
      seg.insertBefore(thumb, seg.firstChild);
      seg.classList.add('has-thumb');
      void thumb.offsetWidth;
      thumb.style.transition = '';
      if (animate !== false && prev) {
        requestAnimationFrame(function () {
          thumb.style.width = box.w + 'px';
          thumb.style.height = box.h + 'px';
          thumb.style.transform = 'translate(' + box.x + 'px,' + box.y + 'px)';
        });
      } else {
        thumb.style.width = box.w + 'px';
        thumb.style.height = box.h + 'px';
        thumb.style.transform = 'translate(' + box.x + 'px,' + box.y + 'px)';
      }
      seg._thumbBox = box;
      return;
    }
    seg.classList.add('has-thumb');
    if (animate === false) thumb.style.transition = 'none';
    thumb.style.width = box.w + 'px';
    thumb.style.height = box.h + 'px';
    thumb.style.transform = 'translate(' + box.x + 'px,' + box.y + 'px)';
    seg._thumbBox = box;
    if (animate === false) {
      void thumb.offsetWidth;
      thumb.style.transition = '';
    }
  }

  function syncSegs(animate) {
    var segs = document.querySelectorAll('.seg');
    for (var i = 0; i < segs.length; i++) {
      if (segs[i].closest('.hidden')) continue;
      placeSegThumb(segs[i], animate !== false);
    }
  }

  var segSyncTimer = null;
  function scheduleSegSync(animate) {
    if (segSyncTimer) return;
    segSyncTimer = requestAnimationFrame(function () {
      segSyncTimer = null;
      syncSegs(animate !== false);
    });
  }

  /* ── iOS 式上下回弹：滚到顶/底还继续拉，页面跟手，松手弹回去 ── */
  (function bindBounce() {
    var MAX = 150, DAMP = 0.55;
    var el = null, startY = 0, active = false, moved = 0;

    function currentPage() {
      var ps = document.querySelectorAll('.page');
      for (var i = 0; i < ps.length; i++) if (!ps[i].classList.contains('hidden')) return ps[i];
      return null;
    }
    function atTop() { return (window.scrollY || document.documentElement.scrollTop || 0) <= 0; }
    function atBottom() { return (window.scrollY || 0) + window.innerHeight >= document.documentElement.scrollHeight - 2; }
    function skip(t) { return !!(t && t.closest && t.closest('.modal, .chat-list, .mc-console, textarea, .tabs, .seg')); }

    document.addEventListener('touchstart', function (e) {
      if (e.touches.length !== 1 || skip(e.target)) { el = null; return; }
      el = currentPage();
      if (!el) return;
      startY = e.touches[0].clientY;
      active = false;
      moved = 0;
    }, { passive: true });

    document.addEventListener('touchmove', function (e) {
      if (!el || e.touches.length !== 1) return;
      var dy = e.touches[0].clientY - startY;
      if (!active) {
        if (dy > 0 && atTop()) active = true;
        else if (dy < 0 && atBottom()) active = true;
        else if (Math.abs(dy) > 8) { el = null; return; }
        else return;
        el.style.transition = 'none';
      }
      moved = Math.min(MAX, Math.abs(dy)) * DAMP * (dy > 0 ? 1 : -1);
      el.style.transform = 'translate3d(0,' + moved.toFixed(1) + 'px,0)';
      if (e.cancelable) e.preventDefault();
    }, { passive: false });

    function release() {
      if (!el) return;
      var node = el;
      el = null; active = false;
      node.style.transition = 'transform .45s cubic-bezier(.22,.61,.36,1)';
      node.style.transform = 'translate3d(0,0,0)';
      setTimeout(function () {
        node.style.transition = '';
        node.style.transform = '';
      }, 470);
    }
    document.addEventListener('touchend', release);
    document.addEventListener('touchcancel', release);
  })();

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
