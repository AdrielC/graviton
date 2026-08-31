package graviton.server.console

private[console] object ConsoleAssets:
  val css: String =
    """
      |:root {
      |  color-scheme: dark;
      |  --ink: #f0f7f5;
      |  --muted: #a9bbb6;
      |  --panel: #141b1a;
      |  --panel-2: #192321;
      |  --line: #2a3935;
      |  --green: #63e6be;
      |  --green-strong: #8cefd1;
      |  --cyan: #66d9ef;
      |  --violet: #b197fc;
      |  --pink: #f783ac;
      |  --warning: #ffd166;
      |  --danger: #ff8787;
      |  --shadow: 0 28px 80px rgba(0, 0, 0, .28);
      |  font-family: "Avenir Next", Avenir, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      |}
      |* { box-sizing: border-box; }
      |html { min-height: 100%; background: #09110f; }
      |::selection { color: #06110e; background: var(--green); }
      |* { scrollbar-width: thin; scrollbar-color: rgba(99, 230, 190, .38) rgba(9, 17, 15, .7); }
      |*::-webkit-scrollbar { width: 10px; height: 10px; }
      |*::-webkit-scrollbar-track { background: rgba(9, 17, 15, .7); }
      |*::-webkit-scrollbar-thumb { border: 2px solid rgba(9, 17, 15, .7); border-radius: 10px; background: rgba(99, 230, 190, .38); }
      |body { min-height: 100vh; margin: 0; color: var(--ink); background:
      |  radial-gradient(circle at 5% -8%, rgba(102, 217, 239, .11), transparent 34rem),
      |  radial-gradient(circle at 96% 1%, rgba(177, 151, 252, .1), transparent 38rem), #09110f; }
      |button, input { font: inherit; }
      |button, a { -webkit-tap-highlight-color: transparent; }
      |button:focus-visible, a:focus-visible, input:focus-visible, .upload-button:focus-within { outline: 2px solid var(--cyan); outline-offset: 3px; }
      |#matrix { position: fixed; inset: 0; width: 100%; height: 100%; opacity: .28; pointer-events: none; }
      |.shell { position: relative; z-index: 1; width: min(1800px, calc(100% - 64px)); margin: 0 auto; padding: 22px 0 36px; }
      |.topbar { display: flex; min-height: 58px; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 16px; }
      |.brand { display: flex; align-items: center; gap: 12px; font-weight: 780; letter-spacing: -.035em; font-size: 1.25rem; }
      |.brand-logo { width: 52px; height: 52px; filter: drop-shadow(0 0 18px rgba(99, 230, 190, .14)); }
      |.top-status { display: flex; align-items: center; gap: 10px; color: var(--muted); font: 600 .76rem ui-monospace, SFMono-Regular, Menlo, monospace; }
      |.live-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--green); box-shadow: 0 0 14px var(--green); }
      |.workspace { min-height: calc(100vh - 132px); border: 1px solid var(--line); border-radius: 14px; background: rgba(20, 27, 26, .97); box-shadow: var(--shadow); overflow: clip; }
      |.commandbar { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 20px; min-height: 92px; padding: 14px 22px 16px; border-bottom: 1px solid var(--line); }
      |.command-title { min-width: 0; }
      |h1 { margin: 0; font-size: clamp(1.5rem, 2.2vw, 2rem); letter-spacing: -.045em; }
      |h2 { margin: 0; font-size: 1.12rem; letter-spacing: -.025em; }
      |.view-tabs { display: flex; align-items: center; gap: 18px; margin-bottom: 8px; }
      |.view-tab { position: relative; min-height: 28px; color: var(--muted); font-size: .76rem; font-weight: 720; text-decoration: none; }
      |.view-tab::after { content: ""; position: absolute; inset: auto 0 0; height: 1px; background: transparent; }
      |.view-tab:hover { color: var(--ink); }
      |.view-tab.active { color: var(--green); }
      |.view-tab.active::after { background: var(--green); box-shadow: 0 0 10px rgba(99, 230, 190, .42); }
      |.command-actions { display: flex; align-items: center; gap: 10px; }
      |.button, .upload-button { min-height: 44px; border: 1px solid var(--line); border-radius: 7px; padding: 0 15px; color: var(--ink); background: transparent; cursor: pointer; font-weight: 720; transition: border-color .16s ease, background .16s ease, color .16s ease; }
      |.button { width: 44px; padding: 0; font-size: 1.05rem; }
      |.icon { display: block; width: 19px; height: 19px; }
      |.button .icon, .icon-button .icon, .folder-form .icon { margin: auto; }
      |.tree-icon { display: grid; place-items: center; width: 19px; height: 19px; }
      |.tree-icon .icon { width: 17px; height: 17px; }
      |.button:hover, .upload-button:hover { border-color: rgba(101, 240, 187, .52); background: rgba(101, 240, 187, .08); }
      |.upload-button { display: inline-flex; align-items: center; gap: 8px; color: var(--green); border-color: rgba(99, 230, 190, .42); background: rgba(99, 230, 190, .08); }
      |.upload-button:hover { color: var(--green-strong); background: rgba(99, 230, 190, .13); }
      |.upload-button input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
      |.content { display: grid; grid-template-columns: 258px minmax(0, 1fr); min-height: calc(100vh - 211px); }
      |.sidebar { padding: 20px 16px; border-right: 1px solid var(--line); background: linear-gradient(180deg, rgba(25, 35, 33, .98), rgba(17, 27, 24, .98)); }
      |.sidebar-title { margin: 2px 8px 8px; color: var(--muted); font: 700 .66rem ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .08em; text-transform: uppercase; }
      |.tree-button { width: 100%; display: flex; align-items: center; gap: 10px; min-height: 46px; border: 1px solid transparent; border-radius: 8px; padding: 0 11px; color: var(--muted); background: transparent; cursor: pointer; text-align: left; text-decoration: none; }
      |.tree-button:hover, .tree-button.active { color: var(--ink); border-color: rgba(99, 230, 190, .14); background: rgba(99, 230, 190, .07); }
      |.tree-button.active { box-shadow: inset 3px 0 0 var(--green); }
      |.folder-form { display: grid; grid-template-columns: 1fr 44px; gap: 6px; margin-top: 12px; }
      |.folder-form input { min-width: 0; height: 44px; border: 1px solid var(--line); border-radius: 9px; padding: 0 10px; color: var(--ink); background: rgba(0, 0, 0, .2); }
      |.folder-form button { border: 1px solid var(--line); border-radius: 9px; color: var(--green); background: rgba(101, 240, 187, .07); cursor: pointer; }
      |.library { min-width: 0; padding: 20px 24px 28px; }
      |.crumbs { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; min-height: 44px; margin: -4px 0 4px; }
      |.crumb { display: inline-flex; align-items: center; min-height: 44px; border: 0; padding: 0; color: var(--muted); background: transparent; cursor: pointer; text-decoration: none; }
      |.crumb:last-child { color: var(--ink); font-weight: 750; }
      |.sep { color: rgba(140, 169, 160, .45); }
      |.summary { display: grid; grid-template-columns: 110px 170px 150px minmax(220px, 1fr); gap: 0; margin: 4px 0 24px; border-block: 1px solid var(--line); color: var(--muted); }
      |.summary-item { min-width: 0; display: grid; align-content: center; min-height: 80px; padding: 12px 18px; border-right: 1px solid var(--line); font: 600 .72rem ui-monospace, SFMono-Regular, Menlo, monospace; }
      |.summary-item:first-child { padding-left: 0; }
      |.summary-item:last-child { border-right: 0; }
      |.summary-item > span { text-transform: uppercase; letter-spacing: .07em; }
      |.summary-item strong { margin-top: 5px; color: var(--ink); font: 780 1.15rem "Avenir Next", Avenir, ui-sans-serif, system-ui, sans-serif; }
      |.summary-item small { margin-top: -18px; justify-self: end; color: var(--muted); }
      |.summary-item.reuse { grid-template-columns: auto auto; align-items: center; }
      |.summary-item.reuse strong { justify-self: end; color: var(--cyan); }
      |.reuse-track { grid-column: 1 / -1; height: 4px; margin-top: 10px; overflow: hidden; background: rgba(255, 255, 255, .07); }
      |.reuse-track i { display: block; width: var(--reuse); height: 100%; background: linear-gradient(90deg, var(--green), var(--cyan)); }
      |.transfer-rail { margin: 0 0 14px; padding: 13px 15px; border: 1px solid var(--line); border-radius: 13px; background: rgba(8, 21, 18, .92); box-shadow: 0 12px 32px rgba(0, 0, 0, .18); }
      |.transfer-rail[hidden], .result-strip[hidden] { display: none; }
      |.upload-progress { display: none; }
      |.upload-progress.active { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 9px; align-items: center; }
      |.progress-track { grid-column: 1 / -1; height: 3px; overflow: hidden; border-radius: 99px; background: rgba(255, 255, 255, .08); }
      |.progress-bar { width: 100%; height: 100%; background: linear-gradient(90deg, var(--green), var(--cyan), var(--violet)); transform: scaleX(0); transform-origin: left center; transition: transform .12s linear; }
      |.progress-copy { overflow: hidden; color: var(--muted); text-overflow: ellipsis; white-space: nowrap; font-size: .82rem; }
      |.progress-value { color: var(--green); font: 700 .75rem ui-monospace, SFMono-Regular, Menlo, monospace; }
      |.result-strip { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 15px; padding: 11px 13px; border: 1px solid rgba(101, 240, 187, .25); border-radius: 10px; color: var(--muted); background: rgba(101, 240, 187, .06); font-size: .82rem; }
      |.transfer-rail .result-strip { margin: 12px 0 0; }
      |.result-strip strong { color: var(--green); }
      |.result-strip.error { border-color: rgba(255, 135, 156, .35); color: var(--danger); background: rgba(255, 135, 156, .06); }
      |.entries { border-top: 1px solid var(--line); }
      |.entries-head { display: grid; grid-template-columns: minmax(260px, 1fr) 110px 120px 106px; gap: 16px; min-height: 38px; align-items: center; border-bottom: 1px solid var(--line); color: var(--muted); font: 700 .64rem ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .08em; text-transform: uppercase; }
      |.entries-head span:last-child { text-align: right; }
      |.entry { display: grid; grid-template-columns: minmax(260px, 1fr) 110px 120px 106px; align-items: center; gap: 16px; min-height: 68px; border-bottom: 1px solid var(--line); }
      |.entry:hover { background: rgba(101, 240, 187, .025); }
      |.entry-name { min-width: 0; display: flex; align-items: center; gap: 11px; }
      |.entry-icon { display: grid; place-items: center; flex: 0 0 42px; height: 42px; border: 1px solid rgba(99, 230, 190, .1); border-radius: 8px; color: var(--green); background: rgba(99, 230, 190, .07); font: 750 .78rem ui-monospace, SFMono-Regular, Menlo, monospace; }
      |.folder .entry-icon { color: var(--violet); background: rgba(168, 148, 255, .09); }
      |.entry-icon.folder-icon .icon { width: 20px; height: 20px; }
      |.name-stack { min-width: 0; }
      |.name { overflow: hidden; color: var(--ink); font-weight: 720; text-overflow: ellipsis; white-space: nowrap; font-size: .95rem; }
      |.hash { overflow: hidden; margin-top: 4px; color: var(--muted); text-overflow: ellipsis; white-space: nowrap; font: 500 .68rem ui-monospace, SFMono-Regular, Menlo, monospace; }
      |.metric { color: var(--muted); font: 600 .74rem ui-monospace, SFMono-Regular, Menlo, monospace; }
      |.dedup { color: var(--cyan); }
      |.row-actions { display: flex; justify-content: flex-end; gap: 7px; }
      |.icon-button { display: grid; place-items: center; width: 44px; height: 44px; border: 1px solid var(--line); border-radius: 9px; color: var(--muted); background: transparent; cursor: pointer; text-decoration: none; }
      |.icon-button:hover { color: var(--ink); border-color: rgba(101, 240, 187, .5); }
      |.icon-button.danger:hover { color: var(--danger); border-color: rgba(255, 135, 156, .45); }
      |.empty { display: grid; place-items: center; min-height: 290px; color: var(--muted); text-align: center; }
      |.empty strong { display: block; margin-bottom: 5px; color: var(--ink); font-size: 1.05rem; }
      |.text-link { min-height: 44px; display: inline-flex; align-items: center; color: var(--muted); font-size: .78rem; font-weight: 700; text-underline-offset: 4px; }
      |.text-link:hover { color: var(--green); }
      |.runtime-workspace { min-height: auto; }
      |.runtime-layout { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .72fr); min-height: calc(100vh - 227px); }
      |.runtime-main { min-width: 0; padding: 0 30px 34px; }
      |.health-summary { min-height: 210px; display: grid; align-content: center; border-bottom: 1px solid var(--line); }
      |.health-heading { display: flex; align-items: center; gap: 13px; }
      |.health-heading h2 { font-size: 2.2rem; letter-spacing: -.035em; }
      |.status-mark { width: 14px; height: 14px; border-radius: 50%; background: var(--danger); box-shadow: 0 4px 20px rgba(255, 135, 135, .35); }
      |.health-summary.ready .status-mark { background: var(--green); box-shadow: 0 4px 20px rgba(99, 230, 190, .34); }
      |.health-summary.degraded .status-mark { background: var(--warning); box-shadow: 0 4px 20px rgba(255, 209, 102, .3); }
      |.health-summary p { max-width: 66ch; margin: 15px 0 0 27px; color: var(--muted); line-height: 1.6; }
      |.check-time { margin: 13px 0 0 27px; color: #8fa59f; font: 600 .68rem ui-monospace, SFMono-Regular, Menlo, monospace; font-variant-numeric: tabular-nums; }
      |.operations-section { padding-top: 28px; }
      |.check-list { border-top: 1px solid var(--line); }
      |.check-row { display: grid; grid-template-columns: 10px minmax(0, 1fr) auto; align-items: center; gap: 12px; min-height: 62px; border-bottom: 1px solid var(--line); }
      |.check-indicator { width: 8px; height: 8px; border-radius: 50%; background: var(--green); }
      |.check-row.degraded .check-indicator { background: var(--warning); }
      |.check-row.unavailable .check-indicator { background: var(--danger); }
      |.check-row.inactive .check-indicator { background: #70817c; }
      |.check-row div { min-width: 0; display: grid; gap: 3px; }
      |.check-row strong { font-size: .82rem; }
      |.check-row div span { overflow: hidden; color: var(--muted); font-size: .74rem; text-overflow: ellipsis; white-space: nowrap; }
      |.check-row em { color: var(--muted); font: 650 .64rem ui-monospace, SFMono-Regular, Menlo, monospace; font-style: normal; text-transform: uppercase; }
      |.check-row.degraded em { color: var(--warning); }
      |.check-row.unavailable em { color: var(--danger); }
      |.placement { padding-top: 28px; }
      |.section-heading, .telemetry-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 18px; margin-bottom: 18px; }
      |.section-heading > span, .telemetry-heading > span { color: var(--muted); font: 650 .68rem ui-monospace, SFMono-Regular, Menlo, monospace; font-variant-numeric: tabular-nums; }
      |.assignment-track { display: flex; height: 7px; margin-bottom: 24px; overflow: hidden; background: rgba(255, 255, 255, .06); }
      |.assignment-track i { display: block; width: var(--width); height: 100%; }
      |.assignment-local { background: var(--green); }
      |.assignment-remote { background: var(--violet); }
      |.capacity-track { height: 7px; margin-bottom: 24px; overflow: hidden; background: rgba(255, 255, 255, .06); }
      |.capacity-track i { display: block; width: var(--width); height: 100%; background: linear-gradient(90deg, var(--green), var(--cyan)); }
      |.runtime-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin: 0; border-top: 1px solid var(--line); }
      |.runtime-facts > div { min-width: 0; display: grid; grid-template-columns: minmax(112px, .55fr) minmax(0, 1fr); gap: 12px; padding: 14px 0; border-bottom: 1px solid var(--line); }
      |.runtime-facts > div:nth-child(odd) { padding-right: 24px; }
      |.runtime-facts > div:nth-child(even) { padding-left: 24px; border-left: 1px solid var(--line); }
      |.runtime-facts dt { color: var(--muted); font-size: .76rem; }
      |.runtime-facts dd { min-width: 0; margin: 0; overflow: hidden; color: var(--ink); text-overflow: ellipsis; white-space: nowrap; font: 650 .76rem ui-monospace, SFMono-Regular, Menlo, monospace; font-variant-numeric: tabular-nums; }
      |.telemetry { padding: 30px 24px; border-left: 1px solid var(--line); background: rgba(10, 20, 17, .5); }
      |.telemetry-heading { margin-bottom: 14px; }
      |.telemetry-row { display: flex; align-items: baseline; justify-content: space-between; gap: 20px; min-height: 48px; padding: 13px 0 11px; border-bottom: 1px solid var(--line); color: var(--muted); font-size: .76rem; }
      |.telemetry-row strong { color: var(--ink); font: 700 .8rem ui-monospace, SFMono-Regular, Menlo, monospace; font-variant-numeric: tabular-nums; }
      |.telemetry-row strong.accent { color: var(--cyan); }
      |.telemetry-row strong.danger { color: var(--danger); }
      |.telemetry-group { margin-top: 30px; padding-top: 22px; border-top: 1px solid var(--line); }
      |.dependency-row { display: grid; gap: 4px; padding: 12px 0; border-bottom: 1px solid var(--line); }
      |.dependency-row strong { font-size: .78rem; }
      |.dependency-row span { color: var(--muted); font: 600 .68rem ui-monospace, SFMono-Regular, Menlo, monospace; }
      |.drop-active .workspace { border-color: var(--cyan); box-shadow: 0 0 0 3px rgba(101, 217, 255, .12), var(--shadow); }
      |@media (max-width: 760px) {
      |  .shell { width: min(100% - 16px, 1800px); padding-top: 10px; }
      |  .brand-logo { width: 44px; height: 44px; }
      |  .top-status span:last-child { display: none; }
      |  .commandbar { grid-template-columns: minmax(0, 1fr) auto; padding: 12px; }
      |  .view-tabs { gap: 14px; }
      |  .command-actions { display: flex; }
      |  .button, .upload-button { justify-content: center; }
      |  .content { grid-template-columns: 1fr; }
      |  .sidebar { padding: 12px; border-right: 0; border-bottom: 1px solid var(--line); }
      |  .sidebar-nav { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 3px; }
      |  .tree-button { width: auto; flex: 0 0 auto; }
      |  .folder-form { max-width: none; }
      |  .library { padding: 12px; }
      |  .summary { grid-template-columns: repeat(3, minmax(86px, 1fr)); overflow: hidden; margin-bottom: 16px; }
      |  .summary-item { min-height: 68px; padding: 10px; }
      |  .summary-item:first-child { padding-left: 10px; }
      |  .summary-item.reuse { grid-column: 1 / -1; border-top: 1px solid var(--line); border-right: 0; }
      |  .summary-item small { display: none; }
      |  .entries-head { display: none; }
      |  .entry { grid-template-columns: minmax(0, 1fr) auto; gap: 9px; padding: 11px 0; }
      |  .entry > .metric { display: none; }
      |  .row-actions { grid-column: 2; grid-row: 1; }
      |  .hash { max-width: 58vw; }
      |  .runtime-layout { grid-template-columns: 1fr; min-height: 0; }
      |  .runtime-main { padding: 0 14px 24px; }
      |  .health-summary { min-height: 190px; }
      |  .health-heading h2 { font-size: 1.9rem; }
      |  .health-summary p, .check-time { margin-left: 27px; }
      |  .runtime-facts { grid-template-columns: 1fr; }
      |  .runtime-facts > div:nth-child(odd), .runtime-facts > div:nth-child(even) { padding: 13px 0; border-left: 0; }
      |  .check-row { grid-template-columns: 10px minmax(0, 1fr); padding: 9px 0; }
      |  .check-row em { grid-column: 2; }
      |  .telemetry { padding: 24px 14px 30px; border-top: 1px solid var(--line); border-left: 0; }
      |  .text-link { display: none; }
      |}
      |@media (prefers-reduced-motion: reduce) {
      |  #matrix { display: none; }
      |  *, *::before, *::after { scroll-behavior: auto !important; transition-duration: .01ms !important; }
      |}
      |""".stripMargin

  val javascript: String =
    """
      |(() => {
      |  const $ = (selector, root = document) => root.querySelector(selector);
      |  const workspace = () => $('#workspace');
      |  const setResult = (message, error = false, detail = '') => {
      |    const target = $('#upload-result');
      |    if (!target) return;
      |    target.className = `result-strip${error ? ' error' : ''}`;
      |    target.setAttribute('role', error ? 'alert' : 'status');
      |    target.replaceChildren();
      |    const primary = document.createElement('span');
      |    const strong = document.createElement('strong');
      |    strong.textContent = message;
      |    primary.append(strong);
      |    target.append(primary);
      |    if (detail) {
      |      const secondary = document.createElement('span');
      |      secondary.textContent = detail;
      |      target.append(secondary);
      |    }
      |    target.hidden = false;
      |  };
      |
      |  const upload = (file) => new Promise((resolve) => {
      |    const root = workspace();
      |    if (!root || !file) return resolve();
      |    const sourceFolder = root.dataset.folder || '';
      |    const refreshUrl = root.dataset.refresh;
      |    const rail = $('#transfer-rail');
      |    const progress = $('#upload-progress');
      |    const bar = $('#progress-bar');
      |    const value = $('#progress-value');
      |    const copy = $('#progress-copy');
      |    if (rail) rail.hidden = false;
      |    progress?.classList.add('active');
      |    if (copy) copy.textContent = file.name;
      |    if (bar) bar.style.transform = 'scaleX(0)';
      |    if (value) value.textContent = '0%';
      |
      |    const query = new URLSearchParams({ name: file.name, session: root.dataset.session });
      |    if (sourceFolder) query.set('folder', sourceFolder);
      |    const request = new XMLHttpRequest();
      |    request.open('POST', `/console/api/uploads?${query}`);
      |    request.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
      |    request.upload.onprogress = (event) => {
      |      if (!event.lengthComputable) return;
      |      const percent = Math.min(100, Math.round((event.loaded / event.total) * 100));
      |      if (bar) bar.style.transform = `scaleX(${percent / 100})`;
      |      if (value) value.textContent = `${percent}%`;
      |    };
      |    request.upload.onload = () => {
      |      if (bar) bar.style.transform = 'scaleX(1)';
      |      if (copy) copy.textContent = `${file.name} · finalizing`;
      |      if (value) value.textContent = 'CAS';
      |    };
      |    request.onerror = () => {
      |      progress?.classList.remove('active');
      |      setResult('Upload failed before the server responded.', true);
      |      resolve();
      |    };
      |    request.onload = () => {
      |      progress?.classList.remove('active');
      |      let payload = {};
      |      try { payload = JSON.parse(request.responseText || '{}'); } catch (_) {}
      |      if (request.status < 200 || request.status >= 300) {
      |        setResult(payload.message || `Upload failed (${request.status}).`, true);
      |        resolve();
      |        return;
      |      }
      |      const total = payload.freshBlocks + payload.duplicateBlocks;
      |      const reused = total === 0 ? 0 : Math.round((payload.duplicateBlocks / total) * 100);
      |      const prefix = payload.referenceCreated ? `${reused}% blocks reused` : 'Already present';
      |      const summary = `${prefix} · ${payload.duplicateBlocks} duplicate / ${total} blocks`;
      |      fetch(refreshUrl, { headers: { Accept: 'text/html' } })
      |        .then(response => {
      |          if (!response.ok) throw new Error(`Refresh failed (${response.status})`);
      |          return response.text();
      |        })
      |        .then(html => {
      |          const current = workspace();
      |          if (current && (current.dataset.folder || '') === sourceFolder) {
      |            current.outerHTML = html;
      |            setResult(summary, false, payload.owner || 'local');
      |          } else {
      |            setResult(summary, false, 'Saved in the upload folder');
      |          }
      |        })
      |        .catch(() => setResult(summary, false, 'Refresh the upload folder to show the reference'))
      |        .finally(resolve);
      |    };
      |    request.send(file);
      |  });
      |  const pending = [];
      |  let draining = false;
      |  const drainUploads = async () => {
      |    if (draining) return;
      |    draining = true;
      |    while (pending.length) await upload(pending.shift());
      |    draining = false;
      |  };
      |  const enqueueUploads = (files) => {
      |    pending.push(...files);
      |    void drainUploads();
      |  };
      |
      |  document.addEventListener('change', (event) => {
      |    const input = event.target.closest?.('#file-input');
      |    if (!input) return;
      |    enqueueUploads(Array.from(input.files || []));
      |    input.value = '';
      |  });
      |  document.addEventListener('dragover', (event) => {
      |    if (!workspace()) return;
      |    event.preventDefault();
      |    document.body.classList.add('drop-active');
      |  });
      |  document.addEventListener('dragleave', (event) => {
      |    if (!event.relatedTarget) document.body.classList.remove('drop-active');
      |  });
      |  document.addEventListener('drop', (event) => {
      |    if (!workspace()) return;
      |    event.preventDefault();
      |    document.body.classList.remove('drop-active');
      |    enqueueUploads(Array.from(event.dataTransfer?.files || []));
      |  });
      |  addEventListener('popstate', () => location.reload());
      |
      |  const canvas = $('#matrix');
      |  if (!canvas || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
      |  const context = canvas.getContext('2d');
      |  let columns = [];
      |  let width = 0;
      |  let height = 0;
      |  let lastPaint = 0;
      |  const resize = () => {
      |    const ratio = Math.min(devicePixelRatio || 1, 2);
      |    width = innerWidth;
      |    height = innerHeight;
      |    canvas.width = width * ratio;
      |    canvas.height = height * ratio;
      |    canvas.style.width = `${width}px`;
      |    canvas.style.height = `${height}px`;
      |    context.setTransform(ratio, 0, 0, ratio, 0, 0);
      |    columns = Array.from({ length: Math.ceil(width / 18) }, (_, index) => columns[index] ?? Math.random() * -(height / 18));
      |  };
      |  const glyphs = '01λΣ⋈⌁アイウエオカキクケコサシスセソ';
      |  const palette = ['rgba(99,230,190,.78)', 'rgba(102,217,239,.74)', 'rgba(177,151,252,.7)', 'rgba(247,131,172,.66)'];
      |  let frame;
      |  const draw = (timestamp = 0) => {
      |    frame = requestAnimationFrame(draw);
      |    if (document.hidden || timestamp - lastPaint < 48) return;
      |    lastPaint = timestamp;
      |    context.globalCompositeOperation = 'destination-out';
      |    context.fillStyle = 'rgba(0, 0, 0, .12)';
      |    context.fillRect(0, 0, width, height);
      |    context.globalCompositeOperation = 'source-over';
      |    context.font = '15px ui-monospace, monospace';
      |    columns.forEach((position, index) => {
      |      context.fillStyle = palette[Math.random() < .84 ? 0 : 1 + Math.floor(Math.random() * 3)];
      |      context.fillText(glyphs[Math.floor(Math.random() * glyphs.length)], index * 18, position * 18);
      |      columns[index] = position * 18 > height && Math.random() > .975 ? Math.random() * -12 : position + .8;
      |    });
      |  };
      |  document.addEventListener('visibilitychange', () => {
      |    if (document.hidden) cancelAnimationFrame(frame);
      |    else draw();
      |  });
      |  addEventListener('resize', resize, { passive: true });
      |  resize();
      |  draw();
      |})();
      |""".stripMargin
