package net.andwebcam

/** The single-page control UI served at "/". */
object WebUi {
    val PAGE: String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>andWebCam</title>
<style>
 :root { color-scheme: dark }
 * { box-sizing: border-box }
 body { margin:0; font-family:system-ui,-apple-system,sans-serif; background:#111; color:#eee }
 header { padding:10px 14px; background:#1b1b1b; display:flex; gap:12px; align-items:center; flex-wrap:wrap }
 header h1 { font-size:16px; margin:0 }
 .grow { flex:1 }
 main { padding:14px }
 .banner { display:grid; grid-template-columns:1fr 1fr; gap:0 26px; background:#1b1b1b; border-radius:8px; padding:6px 14px; margin-bottom:12px }
 .bitem { display:flex; justify-content:space-between; gap:10px; font-size:12.5px; padding:3px 0; border-bottom:1px solid #242424 }
 .bk { color:#888 }
 .bv { color:#eee; font-weight:600 }
 .content { display:flex; gap:14px; align-items:flex-start; flex-wrap:wrap }
 .vleft { flex:1 1 560px; min-width:280px; display:flex; flex-direction:column; gap:10px }
 .vleft .video { flex:0 0 auto; width:100% }
 .video { flex:1 1 560px; min-width:280px; min-height:220px; position:relative; display:flex; align-items:center; justify-content:center; max-height:78vh; overflow:hidden; background:#000; border-radius:8px }
 .video img { display:block; flex:0 0 auto; width:100%; height:auto; max-height:78vh; object-fit:contain; user-select:none; -webkit-user-drag:none }
 .video.fitw img { max-height:none }
 .video.fith img { width:auto; height:78vh; max-width:none }
 .video.oneone img { width:auto; height:auto; max-width:none; max-height:none }
 .video.pannable { cursor:grab }
 .video.grabbing { cursor:grabbing }
 .vload { position:absolute; inset:0; z-index:2; display:none; align-items:center; justify-content:center; color:#777; font-size:13px }
 .video.loading .vload { display:flex }
 .video.loading img { display:none }
 .overlay { position:absolute; inset:0; z-index:4; display:flex; flex-direction:column; gap:6px; align-items:center; justify-content:center; background:#000c; border-radius:8px; color:#bbb; font-size:15px }
 .busy { position:absolute; top:10px; left:10px; z-index:5; display:flex; align-items:center; gap:8px; padding:5px 12px 5px 9px; border:1px solid #33506b; border-radius:20px; background:#000a; color:#cde; font-size:12px }
 .busy .spin { width:11px; height:11px; border-radius:50%; border:2px solid #33506b; border-top-color:#7cc4ff; animation:spin .7s linear infinite }
 .busy.err { border-color:#7a2f2f; color:#f99 }
 .busy.err .spin, .busy.note .spin { display:none }
 .video.applying img { filter:brightness(.7) }
 @keyframes spin { to { transform:rotate(360deg) } }
 .panel { flex:0 1 320px; background:#1b1b1b; border-radius:8px; padding:14px }
 .panel h2 { font-size:12px; text-transform:uppercase; letter-spacing:.06em; color:#8ab; margin:15px 0 6px }
 .panel h2:first-child { margin-top:0 }
 .row { margin:9px 0; display:flex; flex-direction:column; gap:4px }
 label { font-size:13px; color:#bbb }
 select,input[type=text],input[type=password],input[type=number] { background:#0d0d0d; color:#eee; border:1px solid #333; border-radius:6px; padding:7px }
 input[type=range] { width:100% }
 button { background:#2b6cb0; color:#fff; border:0; border-radius:6px; padding:9px 12px; cursor:pointer; font-size:13px }
 button.secondary { background:#333 }
 button.danger { background:#8a3030 }
 button:disabled { opacity:.45; cursor:default }
 .actions { display:flex; gap:8px; flex-wrap:wrap }
 .restartActions { margin-top:10px }
 .filebtn { display:inline-flex; align-items:center; justify-content:center; background:#333; color:#fff; border-radius:6px; padding:9px 12px; cursor:pointer; font-size:13px }
 .filebtn:hover { background:#3d3d3d; color:#fff }
 .dbgact { margin-top:14px }
 .paneClose { margin-left:auto; padding:5px 10px; font-size:15px; line-height:1.1; background:#2b2b2b; color:#9ab }
 .paneClose:hover { background:#3a3a3a; color:#fff }
 .iconbtn { display:inline-flex; align-items:center; justify-content:center; padding:8px 10px; background:#333; color:#b9c6d4 }
 .iconbtn:hover:not(:disabled) { background:#3d3d3d; color:#fff }
 .iconbtn:disabled { opacity:.5; cursor:default }
 .iconbtn svg { width:19px; height:19px; display:block; fill:none; stroke:currentColor; stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round }
 .snapTop { margin-right:6px }
 .seg { display:flex }
 .seg button { background:#262626; color:#bbb; padding:7px 10px; font-size:12px; border-radius:0; border-left:1px solid #111 }
 .seg button:hover { background:#333; color:#eee }
 .seg button:first-child { border-radius:6px 0 0 6px; border-left:0 }
 .seg button:last-child { border-radius:0 6px 6px 0 }
 .seg button.on { background:#2b6cb0; color:#fff }
 .hidden { display:none !important }
 .badge { font-size:11px; padding:3px 8px; border-radius:10px; background:#2b6cb0 }
 .badge.anon { background:#555 }
 .badge.locked { background:#7a2f2f }
 .err { color:#f66; font-size:12px; min-height:1.2em; margin:6px 0 0 }
 .rec { color:#ff1744; font-weight:700 }
 .hint { color:#888; font-size:12px; margin:6px 0 0 }
 .vrow { display:flex; align-items:center; gap:8px; margin:7px 0 }
 .vname { flex:1; font-family:monospace; font-size:12px; color:#dde; overflow:hidden; text-overflow:ellipsis; white-space:nowrap }
 .vmeta { font-size:11px; color:#889; white-space:nowrap }
 .vrow button { padding:5px 9px; font-size:12px }
 .vrow button svg, .vrow a svg { width:13px; height:13px; display:block; margin:0 auto; fill:currentColor }
 #zones { position:absolute; z-index:3; pointer-events:none }
 #zones polygon, #zones polyline, #zones circle, #zones text { vector-effect:non-scaling-stroke }
 .zslider { -webkit-appearance:none; appearance:none; width:110px; height:8px; border-radius:4px; background:#243038; outline:none; cursor:pointer }
 .zslider::-webkit-slider-thumb { -webkit-appearance:none; appearance:none; width:14px; height:14px; border-radius:50%; background:#cfd8dc; border:0; cursor:pointer }
 .zslider::-moz-range-thumb { width:14px; height:14px; border-radius:50%; background:#cfd8dc; border:0; cursor:pointer }
 .zid { display:inline-flex; align-items:center; justify-content:center; width:24px; height:24px; border-radius:50%; border:2px solid #ff8a80; color:#fff; font-size:12px; font-weight:700; flex:0 0 auto }
 .vrow .zslider { margin-left:auto }
 .zrow, .zhdr { display:grid; grid-template-columns:24px 1fr 31px; gap:8px; align-items:center }
 .zrow { margin:7px 0 }
 .zhdr { margin:6px 0 2px; font-size:11px; color:#889 }
 .zhdr span:nth-child(2) { text-align:left }
 .zrow .zslider { width:100%; margin-left:0 }
 .dbg { font-family:monospace; font-size:11px; color:#9aa; background:#0d0d0d; border:1px solid #222; border-radius:6px; padding:6px 8px; max-height:190px; overflow:auto; line-height:1.5 }
 .dbgwrap { width:100% }
 .dbghead { font-size:11px; text-transform:uppercase; letter-spacing:.06em; color:#8ab; margin:0 0 4px }
 .dbghint { text-transform:none; letter-spacing:0; color:#667; margin-left:6px }
 .dbg .dbgline { white-space:pre-wrap }
 .dbg .hi { color:#e8b; }
 .zrow button { padding:5px 9px; font-size:12px }
 .zrow button svg { width:13px; height:13px; display:block; margin:0 auto; fill:currentColor }
 .vrow input[type=range] { padding:0 }
 .vrow a { display:inline-flex; align-items:center; justify-content:center; background:#333; color:#fff; border-radius:6px; padding:5px 9px; font-size:12px; text-decoration:none }
 .vrow a:hover { background:#3d3d3d }
 .modal { position:fixed; inset:0; z-index:60; background:#000c; display:flex; align-items:center; justify-content:center; padding:16px }
 .mbox { background:#1b1b1b; border-radius:8px; padding:14px 16px; max-width:520px; max-height:82vh; overflow:auto }
 .mbox h2 { font-size:12px; text-transform:uppercase; letter-spacing:.06em; color:#8ab; margin:0 }
 .mbox p { margin:9px 0 0; color:#bbb; font-size:12.5px }
 .mbox p:first-of-type { margin-top:12px }
 .mbox code { background:#0d0d0d; padding:2px 4px; border-radius:4px; font-size:11px; word-break:break-all }
 .mbox a { color:#7cc4ff }
 .recmodal { max-width:min(94vw,1100px); padding:10px; width:100% }
 .recmodal h2 { flex:1; margin:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap }
 .recmodal a { display:inline-flex; align-items:center; background:#333; color:#fff; border-radius:6px; padding:7px 12px; font-size:13px; text-decoration:none }
 .recmodal a:hover { background:#3d3d3d }
 .recmodal video { width:100%; max-height:80vh; display:block; background:#000; margin-top:8px }
 #recList { max-height:320px; overflow-y:auto; border:1px solid #222; border-radius:6px; padding-right:2px }
 .vrow.playing { background:#233; border-radius:6px }
 #recPlay { width:100%; margin:9px 0 0; background:#000 }
 .panel a { color:#7cc4ff }
 .foot { color:#556; font-size:11px; text-align:right; padding:6px 4px 0; font-family:monospace }
 .check { display:flex; align-items:center; gap:8px; font-size:13px; color:#bbb }
</style>
</head>
<body>
<header>
  <h1>andWebCam</h1>
  <span id="who" class="badge locked">...</span>
  <span class="grow"></span>
  <button id="snap" class="iconbtn snapTop hidden" title="Save a snapshot" aria-label="Save a snapshot"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/></svg></button>
  <span class="seg" id="scales">
    <button data-scale="fit" title="Fit the whole frame">Fit</button>
    <button data-scale="w" title="Fit width (drag to pan)">W</button>
    <button data-scale="h" title="Fit height (drag to pan)">H</button>
    <button data-scale="1" title="Actual size, 1:1 (drag to pan)">1:1</button>
  </span>
  <button id="paneToggle" class="iconbtn hidden" title="Hide the settings pane" aria-label="Settings"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg></button>
  <button id="recPaneToggle" class="iconbtn hidden" title="Show the recording pane" aria-label="Recording"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="6" fill="#f44336" stroke="none"/></svg></button>
<button id="motionPaneToggle" class="iconbtn hidden" title="Show the motion pane" aria-label="Motion activation"><svg viewBox="0 0 24 24" aria-hidden="true"><polygon points="8 5 16 5 20 19 4 19"/></svg></button>
<button id="logoutBtn" class="iconbtn hidden" title="Log out" aria-label="Log out"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg></button>
  <button id="loginBtn" class="iconbtn" title="Log in" aria-label="Log in"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" y1="12" x2="3" y2="12"/></svg></button>
</header>
<main>
  <div class="banner">
    <div class="bitem"><span class="bk">Status</span><span class="bv" id="i_state">-</span></div>
    <div class="bitem"><span class="bk">Camera</span><span class="bv" id="i_cam">-</span></div>
    <div class="bitem"><span class="bk">Resolution</span><span class="bv" id="i_res">-</span></div>
    <div class="bitem"><span class="bk">Frame rate</span><span class="bv" id="i_fps">-</span></div>
    <div class="bitem"><span class="bk">Battery</span><span class="bv" id="i_batt">-</span></div>
    <div class="bitem"><span class="bk">Viewers</span><span class="bv" id="i_view">-</span></div>
  </div>
  <div class="content">
    <div class="vleft">
    <div class="video loading" id="video">
      <img id="vid" alt="">
      <svg id="zones" class="hidden" preserveAspectRatio="none" viewBox="0 0 1000 1000"></svg>
      <div class="vload">Loading video...</div>
      <div id="busy" class="busy hidden"><span class="spin"></span><span id="busyText">Applying...</span></div>
      <div id="stopped" class="overlay hidden"><div>Streaming is stopped</div><div id="stoppedHint" style="font-size:12px"></div></div>
    </div>
    <div id="motionDebugWrap" class="dbgwrap hidden">
      <div class="dbghead">Motion debug <span class="dbghint">rises over 2 points, newest first</span></div>
      <div id="motionDebug" class="dbg"><p class="hint">No activations yet.</p></div>
    </div>
    </div>
    <div class="panel hidden" id="admin">
      <div class="actions">
        <button id="toggle">Stop streaming</button>
        <button id="paneClose" class="paneClose" title="Hide the settings pane" aria-label="Hide the settings pane">&raquo;</button>
      </div>
      <p class="err" id="actionMsg"></p>

      <div class="row">
        <label>Camera</label>
        <select id="s_facing"><option value="back">Back</option><option value="front">Front</option></select>
      </div>
      <div class="row">
        <label>Resolution</label>
        <select id="s_res"></select>
      </div>
      <div class="row">
        <label>Streaming scale</label>
        <select id="s_scale">
          <option value="25">25%</option>
          <option value="50">50%</option>
          <option value="75">75%</option>
          <option value="100">100%</option>
        </select>
      </div>
      <div class="row">
        <label>Frame rate: <span id="v_fps"></span> fps</label>
        <select id="s_fps"></select>
      </div>
      <div class="row">
        <label>JPEG quality: <span id="v_q"></span></label>
        <input id="s_q" type="range" min="10" max="100" step="5">
      </div>
      <div class="row">
        <label>Zoom: <span id="v_zoom"></span>x</label>
        <input id="s_zoom" type="range" min="1" max="4" step="0.1">
      </div>
      <div class="row">
        <label>Orientation</label>
        <select id="s_orient">
          <option value="0">Landscape</option>
          <option value="90">Portrait</option>
          <option value="180">Landscape (180)</option>
          <option value="270">Portrait (270)</option>
        </select>
      </div>
      <div class="row">
        <label class="check"><input id="s_torch" type="checkbox"> Torch / LED</label>
      </div>

      <h2>Access</h2>
      <div class="row">
        <label class="check"><input id="s_anon" type="checkbox"> Allow anonymous view-only access</label>
      </div>
      <div class="row">
        <label class="check"><input id="auto" type="checkbox"> Start streaming when the service starts</label>
      </div>

      <h2>Service</h2>
      <div class="row">
        <label>Name (host becomes NAME.local)</label>
        <input id="s_name" type="text">
      </div>
      <div class="row">
        <label>HTTPS port</label>
        <input id="s_port" type="number" min="1" max="65535">
      </div>
      <button id="apply">Apply service settings</button>

      <h2>Admin password</h2>
      <div class="row">
        <label>New password</label>
        <input id="p_new" type="password">
      </div>
      <button id="pwset">Change password</button>
      <p class="hint" id="pwmsg"></p>

      <h2>Software Update</h2>
      <div class="actions">
        <label class="filebtn" for="apk">Choose APK file</label>
        <input id="apk" type="file" accept=".apk,application/vnd.android.package-archive" style="display:none">
      </div>
      <button id="doUpdate">Update software</button>
      <p class="hint" id="updmsg"></p>
      <p class="hint"><a href="/api/log?lines=2000" target="_blank" rel="noopener">Download log</a></p>
      <div class="actions restartActions">
        <button id="reboot" class="danger">Restart phone</button>
        <button id="doRestart" class="secondary">Restart service</button>
      </div>
    </div>
    <div class="panel hidden" id="recPane">
      <h2>Record</h2>
      <div class="actions">
        <button id="recBtn">Record</button>
        <button id="recPaneClose" class="paneClose" title="Hide the recording pane" aria-label="Hide the recording pane">&raquo;</button>
      </div>
      <p class="err hidden" id="recMsg"></p>
      <p class="hint hidden" id="recNote"></p>
      <p class="hint" id="recBufferHint">Recording includes 10 second pre-roll buffer</p>

      <h2>Storage</h2>
      <div class="row">
        <label>Cap, percent of free space: <span id="v_capv">10</span></label>
        <input id="s_cap" type="number" min="1" max="100" step="1">
      </div>
      <p class="hint" id="recSpace">Free - · cap - · used -</p>

      <h2>Videos</h2>
      <div id="recList">
        <p class="hint">Nothing recorded yet.</p>
      </div>
      <p class="hint"><a href="#" id="dlHelp">Problems downloading?</a></p>
      <p class="hint"><button id="deleteAllVids" class="secondary">Delete all videos</button></p>
    </div>
    <div class="panel hidden" id="motionPane">
      <h2>Motion activation</h2>
      <div class="actions">
        <button id="motionBtn">Start motion</button>
        <button id="motionPaneClose" class="paneClose" title="Hide the motion pane" aria-label="Hide the motion pane">&raquo;</button>
      </div>
      <p class="err hidden" id="motionMsg"></p>
      <div class="row">
        <label>Pre-roll buffer size: <span id="v_preroll">10</span> seconds</label>
        <input id="s_preroll" type="number" min="0" max="30" step="1">
      </div>
      <p class="hint" id="preRollHint"></p>
      <div class="row">
        <label>Post-motion recording: <span id="v_quiet">30</span> seconds</label>
        <input id="s_quiet" type="number" min="5" max="300" step="1">
      </div>
      <div class="row">
        <label>Sensitivity: <span id="v_level">85</span></label>
        <input id="s_level" type="range" min="0" max="100" step="1">
      </div>
      <div class="row">
        <label>Grid count: <span id="v_grid">32</span></label>
        <input id="s_grid" type="range" min="4" max="160" step="1">
      </div>
      <h2>Activation zones</h2>
      <div class="actions">
        <button id="zoneAdd" class="secondary">Add</button>
        <button id="zoneClear" class="secondary hidden">Clear</button>
        <button id="zoneSave" class="secondary hidden">Save</button>
        <button id="zoneCancel" class="secondary hidden">Cancel</button>
      </div>
      <div class="zhdr"><span>ID</span><span>Minimum object size</span><span></span></div>
      <p class="hint hidden" id="drawHint">Click on the video to place at least three points, then Save.</p>
      <div id="zoneList"><p class="hint">No zones yet.</p></div>
      <div class="actions dbgact"><button id="dbgToggle" class="secondary">Hide debug</button></div>
    </div>
    <div class="panel hidden" id="loginForm">
      <h2>Log in</h2>
      <div class="row">
        <label>Username</label>
        <input id="l_user" type="text" value="admin">
      </div>
      <div class="row">
        <label>Password</label>
        <input id="l_pass" type="password">
      </div>
      <button id="doLogin">Log in</button>
      <p id="loginErr" class="err"></p>
    </div>
  </div>
  <div class="foot" id="foot"></div>
</main>
<div class="modal hidden" id="recPlayerModal">
  <div class="mbox recmodal">
    <div class="actions">
      <h2 id="recPlayerName">Video</h2>
      <a id="recPlayerDl" class="secondary" href="#" download>Download</a>
      <button id="recPlayerClose" class="paneClose" title="Close" aria-label="Close">&times;</button>
    </div>
    <video id="recPlay" controls></video>
  </div>
</div>
<div class="modal hidden" id="dlHelpBox">
  <div class="mbox">
    <div class="actions">
      <h2>Problems downloading?</h2>
      <button id="dlHelpClose" class="paneClose" title="Close" aria-label="Close">&times;</button>
    </div>
    <p>The phone serves HTTPS with a self-signed certificate. Browsing works because you accepted the warning, but downloads do not work in some browsers unless you trust the certificate.</p>
    <p>Windows:</p>
    <p><code id="wCmd"></code></p>
    <p>macOS:</p>
    <p><code id="mCmd"></code></p>
    <p>Linux:</p>
    <p><code id="lCmd"></code></p>
    <p>Firefox downloads without trusting the cert but you can silence the warning at, Settings &rarr; Privacy &amp; Security &rarr; Certificates &rarr; View Certificates &rarr; Authorities &rarr; Import.</p>
    <p><button id="dlCertBtn" class="secondary">Download certificate</button></p>
    <p>Videos can be downloaded with curl using the url obtained from the download button:</p>
    <p><code>curl -k -u admin:PASSWORD -O https://andwebcam.local:8443/video/file/video-yyyymmdd-hhmmss.mp4</code></p>
  </div>
</div>
<script>
function el(id) { return document.getElementById(id); }
function post(path, obj) {
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(obj)
  }).then(function(r) { return r.json().catch(function(){ return {}; }); });
}
// Start/Stop streaming takes a moment to take effect too, so it gets the same
// acknowledgement as a camera setting even though it does not touch the camera
// directly.
function showsBusy(key) { return !!CAMERA_KEYS[key] || key === 'streaming'; }
// The red line under the buttons is for the rare things worth reading; it
// clears itself rather than sitting there looking important.
var actionTimer = null;
function setSetting(key, value) {
  var o = {}; o[key] = value;
  if (showsBusy(key)) { showBusy(); hideBusySoon(6000); }
  post('/api/settings', o).then(function(r) {
    if (r.ok === false) { alert('Not authorized'); hideBusy(); return; }
    if (showsBusy(key)) { hideBusySoon(1200); }
    setTimeout(refresh, 250);
  }).catch(function() { hideBusy(); });
}
var showingVideo = null;
var vidRetry = null;
var vidLoad = null;
var vidPoll = null;
var vidMisses = 0;
// While the first frame is on its way the image element would otherwise show a
// broken icon and its alt text, so it is hidden behind a plain placeholder until
// a frame has actually been decoded (the load event is not delivered for
// multipart streams in every browser, so the decoded size is checked too).
function setLoading(on) {
  el('video').classList.toggle('loading', !!on);
  clearInterval(vidPoll);
  clearTimeout(vidLoad);
  if (on) {
    vidPoll = setInterval(function() {
      if (el('vid').naturalWidth > 0) { setLoading(false); }
    }, 150);
    // A stream that is enabled but has not produced anything for a long time is
    // better shown as an empty frame than as a permanent "Loading video...".
    vidLoad = setTimeout(function() { setLoading(false); }, 10000);
  } else {
    // The picture now has its real size, so the pan limits and cursor change.
    updatePan();
  }
}
function startVideo() {
  var img = el('vid');
  vidMisses = 0;
  img.style.display = '';
  setLoading(true);
  img.src = '/video?t=' + Date.now();
  el('stopped').classList.add('hidden');
}
function stopVideo() {
  clearTimeout(vidRetry);
  clearInterval(vidPoll);
  clearTimeout(vidLoad);
  vidMisses = 0;
  var img = el('vid');
  img.removeAttribute('src');
  img.style.display = 'none';
  el('video').classList.remove('loading');
  el('stopped').classList.remove('hidden');
}
function applyVideo(s) {
  var want = !!s.streaming;
  if (showingVideo === want) { return; }
  showingVideo = want;
  if (want) { startVideo(); } else { stopVideo(); }
}
// A stream that stops delivering says nothing about it. A browser that lost the
// multipart boundary, or a connection that died while the machine was asleep,
// leaves an element that never decodes a frame and never fires an error, so the
// page can sit there with the stream enabled and show nothing for ever - and
// because the page already believes it is showing the stream, asking for it
// again changes nothing. Reloading the source is what stopping and starting did
// by hand, so that is what is done here once a few polls have seen no frame.
function checkVideo() {
  if (el('vid').naturalWidth > 0) { vidMisses = 0; return; }
  if (++vidMisses >= 3) { startVideo(); }
}
// Coming back to the tab, or to the machine: the stream connection did not
// survive the time away and will never report an error, so it is replaced
// rather than trusted. A timer that did not run for a long time is what a
// suspended machine looks like, and a tab becoming visible is what a switch
// looks like; both want a fresh connection.
var lastPoll = Date.now();
document.addEventListener('visibilitychange', function() {
  if (!document.hidden && window.S && window.S.streaming) { startVideo(); }
});
// The stream can be cut by the network; reconnect instead of showing a dead image.
el('vid').onload = function() { setLoading(false); };
el('vid').onerror = function() {
  setLoading(true);
  if (window.S && window.S.streaming) {
    clearTimeout(vidRetry);
    vidRetry = setTimeout(startVideo, 1000);
  }
};
// Scaling: fit the whole frame, fit the width, fit the height, or actual size.
// Anything larger than the viewing area can be dragged to pan around it.
var scaleMode = 'fit';
var panX = 0, panY = 0;
var drag = null;
function scalePref(v) {
  try {
    if (v === undefined) { return localStorage.getItem('awcScale'); }
    localStorage.setItem('awcScale', v);
  } catch (e) {}
  return null;
}
function panLimits() {
  var img = el('vid'), box = el('video');
  return {
    x: Math.max(0, (img.offsetWidth - box.clientWidth) / 2),
    y: Math.max(0, (img.offsetHeight - box.clientHeight) / 2)
  };
}
function applyPan() {
  el('vid').style.transform = (panX || panY) ? 'translate(' + panX + 'px,' + panY + 'px)' : '';
  placeZones();
}
function updatePan() {
  var l = panLimits();
  panX = Math.max(-l.x, Math.min(l.x, panX));
  panY = Math.max(-l.y, Math.min(l.y, panY));
  applyPan();
  el('video').classList.toggle('pannable', l.x > 1 || l.y > 1);
}
function applyScale() {
  var box = el('video');
  box.classList.remove('fitw', 'fith', 'oneone');
  if (scaleMode === 'w') { box.classList.add('fitw'); }
  if (scaleMode === 'h') { box.classList.add('fith'); }
  if (scaleMode === '1') { box.classList.add('oneone'); }
  var b = document.querySelectorAll('#scales button');
  for (var i = 0; i < b.length; i++) {
    b[i].classList.toggle('on', b[i].getAttribute('data-scale') === scaleMode);
  }
  panX = 0;
  panY = 0;
  updatePan();
}
var scaleBtns = document.querySelectorAll('#scales button');
for (var i = 0; i < scaleBtns.length; i++) {
  scaleBtns[i].onclick = function() {
    scaleMode = this.getAttribute('data-scale');
    scalePref(scaleMode);
    applyScale();
  };
}
el('vid').ondragstart = function() { return false; };
el('video').addEventListener('pointerdown', function(e) {
  var l = panLimits();
  if (drawing || (l.x < 1 && l.y < 1) || !el('stopped').classList.contains('hidden')) { return; }
  drag = { x: e.clientX, y: e.clientY, px: panX, py: panY };
  el('video').classList.add('grabbing');
  try { el('video').setPointerCapture(e.pointerId); } catch (err) {}
  e.preventDefault();
});
el('video').addEventListener('pointermove', function(e) {
  if (!drag) { return; }
  var l = panLimits();
  panX = Math.max(-l.x, Math.min(l.x, drag.px + (e.clientX - drag.x)));
  panY = Math.max(-l.y, Math.min(l.y, drag.py + (e.clientY - drag.y)));
  applyPan();
});
function endDrag() {
  drag = null;
  el('video').classList.remove('grabbing');
}
el('video').addEventListener('pointerup', endDrag);
el('video').addEventListener('pointercancel', endDrag);
// The scroll wheel pans the picture while there is something to pan to; at an
// edge it lets the page scroll again.
el('video').addEventListener('wheel', function(e) {
  var l = panLimits();
  var unit = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? el('video').clientHeight : 1);
  var dy = e.deltaY * unit;
  var dx = e.deltaX * unit;
  var moved = false;
  if (l.y > 1) {
    var ny = Math.max(-l.y, Math.min(l.y, panY - dy));
    if (ny !== panY) { panY = ny; moved = true; }
  } else if (l.x > 1 && dy) {
    var nx = Math.max(-l.x, Math.min(l.x, panX - dy));
    if (nx !== panX) { panX = nx; moved = true; }
  }
  if (l.x > 1 && dx) {
    var mx = Math.max(-l.x, Math.min(l.x, panX - dx));
    if (mx !== panX) { panX = mx; moved = true; }
  }
  if (moved) {
    e.preventDefault();
    applyPan();
  }
}, { passive: false });
el('vid').addEventListener('load', function() { updatePan(); drawZones(); });
window.addEventListener('resize', function() { updatePan(); drawZones(); });
// Camera changes take a moment to show up in the stream and there is no
// dependable signal for exactly when they do, so show a spinner from the moment
// the control is used until shortly after the phone acknowledges it.
var CAMERA_KEYS = { facing:1, resolution:1, fps:1, quality:1, zoom:1, orientation:1, torch:1 };
var busyTimer = null;
function showBusy(text) {
  el('busy').classList.remove('hidden');
  el('busyText').textContent = text || 'Applying...';
  el('video').classList.add('applying');
}
function hideBusy() {
  clearTimeout(busyTimer);
  el('busy').classList.add('hidden');
  el('busy').classList.remove('err', 'note');
  el('video').classList.remove('applying');
}
// A plain note in the same chip, with no spinner: nothing is being applied.
function showBusyNote(text) {
  clearTimeout(busyTimer);
  el('busy').classList.remove('hidden', 'err');
  el('busy').classList.add('note');
  el('busyText').textContent = text;
  el('video').classList.remove('applying');
  busyTimer = setTimeout(hideBusy, 3000);
}
// A failure is shown in the same chip, because the snapshot button is in the
// header and view-only users have no settings panel to write into.
function showBusyError(text) {
  clearTimeout(busyTimer);
  el('busy').classList.remove('hidden');
  el('busy').classList.add('err');
  el('busyText').textContent = text;
  el('video').classList.remove('applying');
  busyTimer = setTimeout(hideBusy, 5000);
}
function hideBusySoon(ms) {
  clearTimeout(busyTimer);
  busyTimer = setTimeout(hideBusy, ms);
}
// The Status banner item. While a recording runs - by hand or from motion -
// a red record dot and the elapsed time join the state, and a once-a-second
// ticker keeps the clock moving between status polls.
function updateStatusText(s) {
  var st = s.locked ? 'locked'
    : (!s.streaming ? 'streaming off' : (s.cameraActive ? 'streaming' : 'idle (no viewers)'));
  if (s.motion) { st += ', motion on'; }
  if (s.recording && s.recStart) {
    st += ' <span class="rec">&#9679; ' + recFmtElapsed(Date.now() - s.recStart) + '</span>';
  }
  el('i_state').innerHTML = st;
}

function recFmtElapsed(ms) {
  var t = Math.max(0, Math.floor(ms / 1000));
  var h = Math.floor(t / 3600);
  var m = Math.floor((t % 3600) / 60);
  var s = t % 60;
  var mm = (m < 10 ? '0' : '') + m;
  var ss = (s < 10 ? '0' : '') + s;
  return h ? h + ':' + mm + ':' + ss : mm + ':' + ss;
}

setInterval(function() {
  var s = window.S;
  if (s && s.recording && s.recStart) { updateStatusText(s); }
}, 1000);

function refresh() {
  fetch('/api/status').then(function(r) { return r.json(); }).then(function(s) {
    var now = Date.now();
    var gap = now - lastPoll;
    lastPoll = now;
    window.S = s;
    el('who').textContent = s.admin ? 'admin' : (s.anonView ? 'view-only' : 'locked');
    el('who').className = 'badge ' + (s.admin ? '' : (s.anonView ? 'anon' : 'locked'));
    el('who').title = s.auth ? 'Signed in with ' + s.auth : '';
    updateStatusText(s);
    var pr = s.preRoll || 0;
    el('recBufferHint').textContent = pr > 0
      ? 'Recording includes a ' + pr + '-second pre-roll buffer'
      : 'No pre-roll buffer';
    el('i_cam').textContent = s.facing || '-';
    el('i_res').textContent = (s.width || '?') + 'x' + (s.height || '?');
    el('i_fps').textContent = (s.fpsActive || 0) + ' / ' + (s.fps || '?') + ' fps';
    el('i_batt').textContent = s.battery == null ? '-'
      : s.battery + '%' + (s.charging ? ' charging' : '');
    el('i_view').textContent = (s.clients || 0);
    applyVideo(s);
    // A poll that was due long ago means the machine was suspended, and the
    // stream connection went with it: nothing about the element says so.
    if (gap > 10000 && s.streaming) { startVideo(); }
    if (s.streaming && !s.locked) { checkVideo(); }
    if (s.locked) {
      // Signed out: there is no stream to announce, only the way back in.
      el('stopped').classList.add('hidden');
      el('loginForm').classList.remove('hidden');
    }

    el('foot').textContent = 'andWebCam ' + (s.version || '?') + (s.build ? ' · ' + s.build : '');
    el('loginBtn').classList.toggle('hidden', !!s.admin);
    el('logoutBtn').classList.toggle('hidden', !s.admin);
    // View-only users may save a frame too: the server already allows them.
    el('snap').classList.toggle('hidden', !s.admin && !s.anonView);
    applyPane();
    if (!s.admin) { return; }

    // Not while a click is still waiting for its answer, or the poll would
    // undo the "Starting.../Stopping..." label.
    if (!el('toggle').disabled) {
      el('toggle').textContent = s.streaming ? 'Stop streaming' : 'Start streaming';
    }
    // Restarting needs device owner; without it there is nothing the button
    // could do, so it is not offered.
    el('reboot').classList.toggle('hidden', !s.canReboot);
    // The footer already carries the version, so the post-update note is
    // only useful while an update is actually being driven from this page.
    if (el('s_res').options.length !== (s.resolutions || []).length) {
      el('s_res').innerHTML = '';
      (s.resolutions || []).forEach(function(x) { var o = document.createElement('option'); o.value = x; o.textContent = x; el('s_res').appendChild(o); });
    }
    if (el('s_fps').options.length !== (s.fpsOptions || []).length) {
      el('s_fps').innerHTML = '';
      (s.fpsOptions || []).forEach(function(x) { var o = document.createElement('option'); o.value = x; o.textContent = x; el('s_fps').appendChild(o); });
    }
    if (document.activeElement !== el('s_facing')) { el('s_facing').value = s.facing || 'back'; }
    if (document.activeElement !== el('s_res')) { el('s_res').value = (s.width || '') + 'x' + (s.height || ''); }
    if (document.activeElement !== el('s_scale')) { el('s_scale').value = String(s.streamScale || 50); }
    if (document.activeElement !== el('s_fps')) { el('s_fps').value = String(s.fps || 15); }
    if (document.activeElement !== el('s_q')) { el('s_q').value = s.quality || 80; el('v_q').textContent = s.quality || 80; }
    if (document.activeElement !== el('s_zoom')) { el('s_zoom').max = String(s.maxZoom || 4); el('s_zoom').value = s.zoom || 1; el('v_zoom').textContent = Math.round((s.zoom || 1) * 10) / 10; }
    if (document.activeElement !== el('s_orient')) { el('s_orient').value = String(s.orientation || 0); }
    if (document.activeElement !== el('s_name')) { el('s_name').value = s.serviceName || 'andwebcam'; }
    if (document.activeElement !== el('s_port')) { el('s_port').value = s.port || 8443; }
    if (document.activeElement !== el('s_torch')) { el('s_torch').checked = !!s.torch && !!s.torchAvailable; }
    el('s_torch').disabled = !s.torchAvailable;
    if (document.activeElement !== el('s_anon')) { el('s_anon').checked = !!s.anonView; }
    if (document.activeElement !== el('auto')) { el('auto').checked = !!s.autoStartStreaming; }
  }).catch(function() {
    el('i_state').textContent = 'unreachable';
  });
}
// The same restart the phone's own screen offers: a fresh pass through the
// service's startup, which fixes states like a camera the framework has
// stopped trusting. The answer comes before the restart runs.
el('doRestart').onclick = function() {
  var b = this;
  b.disabled = true;
  b.textContent = 'Restarting...';
  post('/api/service/restart', {}).then(function() {
    setTimeout(function() {
      b.disabled = false;
      b.textContent = 'Restart service';
    }, 4000);
  }).catch(function() {
    b.disabled = false;
    b.textContent = 'Restart service';
  });
};
el('s_facing').onchange = function() { setSetting('facing', this.value); };
el('s_res').onchange = function() {
  // Zones are drawn for the aspect ratio they were made in: a resolution
  // with a different one invalidates them, so say so before it happens.
  var s = window.S || {};
  if ((s.motionZones || 0) > 0 && s.motionAspect) {
    var parts = this.value.split('x');
    var newA = (parseFloat(parts[0]) / parseFloat(parts[1])) || 0;
    if (newA && Math.abs(s.motionAspect - newA) / Math.max(s.motionAspect, newA) > 0.01) {
      if (!confirm('This resolution has a different aspect ratio: the ' + s.motionZones +
          ' drawn zone' + (s.motionZones === 1 ? '' : 's') + ' will be cleared. Continue?')) {
        this.value = s.width + 'x' + s.height;
        return;
      }
    }
  }
  setSetting('resolution', this.value);
};
el('s_fps').onchange = function() { setSetting('fps', this.value); };
el('s_scale').onchange = function() { setSetting('streamScale', this.value); };
el('s_q').oninput = function() { el('v_q').textContent = this.value; };
el('s_q').onchange = function() { setSetting('quality', this.value); };
el('s_zoom').oninput = function() { el('v_zoom').textContent = Math.round(parseFloat(this.value) * 10) / 10; };
el('s_zoom').onchange = function() { setSetting('zoom', this.value); };
el('s_orient').onchange = function() { setSetting('orientation', this.value); };
el('s_torch').onchange = function() { setSetting('torch', this.checked); };
el('s_anon').onchange = function() { setSetting('anonView', this.checked); };
el('auto').onchange = function() { setSetting('autoStartStreaming', this.checked); };
// The button says what it will do straight away (and refuses a second click)
// rather than waiting for the answer, which takes a moment on a busy phone.
el('toggle').onclick = function() {
  var on = !(window.S && window.S.streaming);
  var btn = el('toggle');
  var was = btn.textContent;
  btn.disabled = true;
  btn.textContent = on ? 'Starting...' : 'Stopping...';
  showBusy();
  hideBusySoon(6000);
  post('/api/settings', { streaming: on }).then(function(r) {
    btn.disabled = false;
    if (r.ok === false) {
      btn.textContent = was;
      hideBusy();
      alert('Not authorized');
      return;
    }
    hideBusySoon(1200);
    setTimeout(refresh, 250);
  }).catch(function() {
    btn.disabled = false;
    btn.textContent = was;
    hideBusy();
  });
};
function actionMsg(text) {
  el('actionMsg').textContent = text;
  clearTimeout(actionTimer);
  if (text) { actionTimer = setTimeout(function() { el('actionMsg').textContent = ''; }, 5000); }
}
el('reboot').onclick = function() {
  if (!confirm('Restart the phone now?')) { return; }
  actionMsg('Rebooting...');
  post('/api/reboot', {}).then(function(r) {
    if (r.ok === false) { actionMsg(r.error || 'Reboot failed'); }
  });
};
// A snapshot is the frame as the camera is configured right now: the phone
// sends a JPEG at the current resolution, not a scaling of what is on screen.
// Where the browser can offer a real save dialog it is asked for before the
// request, while the click still counts as the user gesture it requires.
function snapName() {
  var d = new Date();
  function p(n) { return (n < 10 ? '0' : '') + n; }
  return 'andWebCam-' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' +
    p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds()) + '.jpg';
}
function downloadBlob(blob, name) {
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(function() { URL.revokeObjectURL(url); }, 5000);
}
function grabSnapshot(name, handle) {
  var btn = el('snap');
  btn.disabled = true;
  showBusy('Taking snapshot...');
  hideBusySoon(15000);
  fetch('/snapshot', { cache: 'no-store' }).then(function(r) {
    if (r.ok) { return r.blob(); }
    if (r.status === 401) { throw new Error('not logged in'); }
    if (r.status === 503) { throw new Error('the camera produced no frame'); }
    throw new Error('request failed (' + r.status + ')');
  }).then(function(blob) {
    if (!handle) { downloadBlob(blob, name); return; }
    return handle.createWritable().then(function(w) {
      return w.write(blob).then(function() { return w.close(); });
    });
  }).then(function() {
    hideBusy();
  }).catch(function(e) {
    showBusyError('Snapshot failed: ' + (e && e.message ? e.message : 'unknown error'));
  }).then(function() {
    btn.disabled = false;
  });
}
el('snap').onclick = function() {
  if (el('snap').disabled) { return; }
  // Nothing to photograph while streaming is stopped - and opening the camera
  // just for this produced a black first frame, which is worse than a note.
  if (!(window.S && window.S.streaming)) {
    showBusyNote('Turn streaming on to take a snapshot');
    return;
  }
  var name = snapName();
  var pick = null;
  if (window.showSaveFilePicker) {
    try {
      pick = window.showSaveFilePicker({
        suggestedName: name,
        types: [{ description: 'JPEG image', accept: { 'image/jpeg': ['.jpg', '.jpeg'] } }]
      });
    } catch (e) { pick = null; }
  }
  if (!pick) { grabSnapshot(name, null); return; }
  pick.then(function(h) { grabSnapshot(name, h); }).catch(function(e) {
    // Cancelling the dialog is not an error, and no picture was taken.
    if (e && (e.name === 'AbortError' || e.name === 'NotAllowedError')) { return; }
    grabSnapshot(name, null);
  });
};
el('apply').onclick = function() {
  post('/api/settings', { serviceName: el('s_name').value, port: el('s_port').value }).then(function(r) {
    if (r.ok === false) { alert('Not authorized'); }
  });
};
el('pwset').onclick = function() {
  var p = el('p_new').value;
  if (p.length < 4) { el('pwmsg').textContent = 'Too short'; return; }
  post('/api/password', { password: p }).then(function(r) {
    el('pwmsg').textContent = r.ok ? 'Password changed.' : 'Failed.';
    el('p_new').value = '';
    setTimeout(refresh, 300);
  });
};
// Software update. The APK is sent as the raw body of POST /api/update, which
// is also how to do it from a script (curl --data-binary @app.apk).
// The install replaces this app, so the phone stops answering for a few
// seconds; the page waits for the build id to change and then reloads itself.
var updateBusy = false;
function updateMsg(t) { el('updmsg').textContent = t; }
el('apk').onchange = function() {
  if (!updateBusy && this.files.length) { updateMsg(this.files[0].name); }
};
el('doUpdate').onclick = function() {
  if (updateBusy) { return; }
  var f = el('apk').files[0];
  if (!f) { updateMsg('Choose an APK file first.'); return; }
  if (!/\.apk$/i.test(f.name) && f.type !== 'application/vnd.android.package-archive') {
    if (!confirm('"' + f.name + '" does not look like an APK. Send it anyway?')) { return; }
  }
  if (!confirm('Install ' + f.name + ' on the phone?')) { return; }
  var b = el('doUpdate');
  var before = ((window.S && window.S.build) || '') + '|' + ((window.S && window.S.version) || '');
  updateBusy = true;
  b.disabled = true;
  b.textContent = 'Uploading...';
  updateMsg('Uploading ' + f.name + ' (' + Math.round(f.size / 1024) + ' kB)...');
  fetch('/api/update', {
    method: 'POST',
    headers: { 'Content-Type': 'application/vnd.android.package-archive' },
    body: f
  }).then(function(r) { return r.json().catch(function() { return {}; }); })
    .then(function(r) {
      if (!r.ok) {
        updateBusy = false; b.disabled = false; b.textContent = 'Update software';
        updateMsg(r.error || 'The update was refused.');
        return;
      }
      b.textContent = 'Installing...';
      updateMsg(r.message || 'Installing...');
      var tries = 0;
      var went = false;   // the phone stopped answering: it is being replaced
      var t = setInterval(function() {
        tries++;
        fetch('/api/status').then(function(x) { return x.json(); }).then(function(s) {
          var now = (s.build || '') + '|' + (s.version || '');
          // The app is back: it has been replaced, whether or not the build
          // id changed (rebuilding the same commit does not change it).
          if (s.build && (now !== before || went)) { clearInterval(t); location.reload(); }
          went = false;
        }).catch(function() { went = true; });
        if (tries > 60) {
          clearInterval(t);
          updateBusy = false; b.disabled = false; b.textContent = 'Update software';
          updateMsg('No answer from the phone. Reload the page to see how it went.');
          setTimeout(refresh, 500);
        }
      }, 3000);
    })
    .catch(function() {
      updateBusy = false; b.disabled = false; b.textContent = 'Update software';
      updateMsg('The upload did not finish. Check that the phone is still reachable.');
    });
};
// The panes share one slot next to the video: opening one closes the others
// and the choice lasts for the page: logging in or reloading always lands
// on the settings pane, however the last visit ended.
var PANES = { paneToggle: 'settings', recPaneToggle: 'recording', motionPaneToggle: 'motion' };
var PANE_TITLES = { settings: 'the settings pane', recording: 'the recording pane', motion: 'the motion pane' };
var pane = 'settings';
var debugOpen = true;
function applyPane() {
  var admin = !!(window.S && window.S.admin);
  var open = pane;
  for (var btn in PANES) {
    var p = PANES[btn];
    var b = el(btn);
    b.classList.toggle('hidden', !admin);
    var isOpen = admin && open === p;
    b.title = (isOpen ? 'Hide ' : 'Show ') + PANE_TITLES[p];
    b.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
  }
  el('admin').classList.toggle('hidden', !(admin && open === 'settings'));
  el('recPane').classList.toggle('hidden', !(admin && open === 'recording'));
  el('motionPane').classList.toggle('hidden', !(admin && open === 'motion'));
  el('motionDebugWrap').classList.toggle('hidden', !(admin && open === 'motion' && debugOpen));
  // The recording pane polls its own list while it is open, and the player
  // does not keep running behind a closed one. The motion pane polls the
  // zone levels the same way.
  if (admin && open === 'recording') {
    if (!recPoll) {
      recPoll = setInterval(recPollTick, 2500);
      recRefresh();
    }
  } else if (recPoll) {
    clearInterval(recPoll);
    recPoll = null;
    recStopPlayer();
  }
  if (admin && open === 'motion') {
    if (!motionPoll) {
      motionPoll = setInterval(motionTick, 1000);
      motionTick();
    }
    el('zones').classList.remove('hidden');
    drawZones();
  } else {
    if (motionPoll) {
      clearInterval(motionPoll);
      motionPoll = null;
    }
    el('zones').classList.add('hidden');
    if (drawing) { drawing = null; updateDrawUi(); }
  }
  updatePan();
}
function openPane(which) {
  // The button of the pane that is open hides it; any other button switches.
  pane = pane === which ? 'hidden' : which;
  applyPane();
}
el('paneToggle').onclick = function() { openPane('settings'); };
el('recPaneToggle').onclick = function() { openPane('recording'); };
el('motionPaneToggle').onclick = function() { openPane('motion'); };
el('dbgToggle').onclick = function() {
  debugOpen = !debugOpen;
  this.textContent = debugOpen ? 'Hide debug' : 'Show debug';
  applyPane();
};
el('paneClose').onclick = el('recPaneClose').onclick = el('motionPaneClose').onclick = function() {
  pane = 'hidden';
  applyPane();
};
el('loginBtn').onclick = function() { el('loginForm').classList.toggle('hidden'); };
el('logoutBtn').onclick = function() { post('/api/logout', {}).then(function() { location.reload(); }); };
function doLogin() {
  var b = el('doLogin');
  if (b.disabled) { return; }
  b.disabled = true;
  b.textContent = 'Logging in...';
  el('loginErr').textContent = '';
  post('/api/login', { user: el('l_user').value, pass: el('l_pass').value }).then(function(r) {
    if (r.ok) { location.reload(); return; }
    b.disabled = false;
    b.textContent = 'Log in';
    el('loginErr').textContent = 'Wrong username or password';
  }).catch(function() {
    b.disabled = false;
    b.textContent = 'Log in';
    el('loginErr').textContent = 'Could not reach the phone';
  });
}
el('doLogin').onclick = doLogin;
// There is no <form> around these fields, so Enter does nothing on its own.
function loginKey(e) {
  if (e.key === 'Enter') { e.preventDefault(); doLogin(); }
}
el('l_user').addEventListener('keydown', loginKey);
el('l_pass').addEventListener('keydown', loginKey);
// ---- the recording pane --------------------------------------------------
// The list is polled on its own timer, only while the pane is open (applyPane
// starts and stops it), because every poll lists the folder on the phone.
var recPoll = null;
var motionPoll = null;
var recCount = 0;
var recListKey = '';
var motionDbgKey = -1;
var motionSince = 0;
var recPlaying = null;
var recNoteSeen = null;
var recNoteArmed = false;
// Glyphs for the video rows: a record dot's worth of pixels each, and they
// read the same on every platform, unlike font-dependent symbols.
var ICON_PLAY = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M5 3.1 12.7 8 5 12.9z"/></svg>';
var ICON_STOP = '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="4" y="4" width="8" height="8" rx="1.2"/></svg>';
var ICON_BIN = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6.2 1.6h3.6v1.1h3.6v1.5H2.6V2.7h3.6zM3.5 5.5h9l-.7 8.9H4.2z"/></svg>';
var ICON_DOWN = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M7.2 1.8h1.6v5l2.1-2.1 1.1 1.1L8 9.9 4 5.8l1.1-1.1 2.1 2.1z"/><path d="M2.8 11.2h10.4v1.5H2.8z"/></svg>';
function recFmtSize(b) {
  if (b == null || b === undefined || isNaN(b)) { return '-'; }
  if (b < 1024) { return b + ' B'; }
  if (b < 1024 * 1024) { return (b / 1024).toFixed(1) + ' KB'; }
  return (b / 1024 / 1024).toFixed(1) + ' MB';
}
// Whole megabytes with thousands separators, the way a person reads them.
function recMB(b) {
  if (b == null || b === undefined || isNaN(b)) { return '-'; }
  return group(Math.round(b / 1048576));
}
// One decimal place, still grouped: "1,234.5".
function recMB1(b) {
  if (b == null || b === undefined || isNaN(b)) { return '-'; }
  var s = (Math.round(b / 104857.6) / 10).toFixed(1);
  var p = s.split('.');
  return group(parseInt(p[0], 10)) + '.' + p[1];
}
function group(n) {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}
function recFmtDur(s) {
  if (!s || s <= 0) { return ''; }
  s = Math.round(s);
  var m = Math.floor(s / 60);
  return (m ? m + ' min ' : '') + (s % 60) + ' s';
}
function recMsg(text) {
  var m = el('recMsg');
  m.textContent = text || '';
  m.classList.toggle('hidden', !text);
  if (text) {
    clearTimeout(recMsg.t);
    recMsg.t = setTimeout(function() { recMsg(''); }, 8000);
  }
}
function recStopPlayer() {
  var v = el('recPlay');
  try { v.pause(); } catch (e) {}
  v.removeAttribute('src');
  el('recPlayerModal').classList.add('hidden');
  recPlaying = null;
  updatePlayButtons();
}
// Keep each row's icon and highlight in step with what is playing in the
// modal. The rows are never rebuilt for this, so the list does not move.
function updatePlayButtons() {
  var b = el('recList').querySelectorAll('button[data-play]');
  for (var i = 0; i < b.length; i++) {
    var on = b[i].getAttribute('data-play') === recPlaying;
    b[i].innerHTML = on ? ICON_STOP : ICON_PLAY;
    b[i].title = on ? 'Stop' : 'Play';
    b[i].setAttribute('aria-label', b[i].title + ' ' + b[i].getAttribute('data-play'));
    b[i].parentNode.classList.toggle('playing', on);
  }
}

function recPlay(name) {
  recPlaying = name;
  updatePlayButtons();
  el('recPlayerName').textContent = name;
  var dl = el('recPlayerDl');
  dl.href = '/video/file/' + encodeURIComponent(name);
  dl.download = name;
  el('recPlayerModal').classList.remove('hidden');
  var v = el('recPlay');
  // The name is in the path so a browser's own download uses it; the query
  // form is still accepted for links made before this.
  v.src = '/video/file/' + encodeURIComponent(name);
  v.play().catch(function() {});
}
function recRefresh() {
  if (!(window.S && window.S.admin)) { return; }
  if (el('recPane').classList.contains('hidden')) { return; }
  fetch('/api/record/list').then(function(r) { return r.json(); }).then(function(d) {
    recCount = (d.videos || []).length;
    el('deleteAllVids').disabled = !recCount;
    el('deleteAllVids').classList.toggle('hidden', !recCount);
    var btn = el('recBtn');
    btn.classList.toggle('danger', !!d.recording);
    if (!btn.disabled) {
      btn.textContent = d.recording ? 'Stop recording' : 'Record';
    }
    // Without a folder there is nowhere to write, and with streaming off the
    // phone is meant to be idle: both stop a session before it starts.
    var S = window.S || {};
    var canRecord = !!S.logFolder && !!S.streaming;
    btn.disabled = !canRecord && !d.recording;
    // The note is the last thing the recorder said. A page load must not
    // replay an old one, so the first answer only records what it saw; a
    // change after that is news.
    if (recNoteArmed) {
      if (d.note && d.note !== recNoteSeen) { recNoteSeen = d.note; recMsg(d.note); }
    } else {
      recNoteSeen = d.note;
      recNoteArmed = true;
    }
    if (document.activeElement !== el('s_cap')) { el('s_cap').value = d.capPercent || 10; }
    el('v_capv').textContent = d.capPercent || 10;
    el('s_cap').disabled = !!d.recording;
    el('recSpace').textContent = d.free == null
      ? 'Free - · cap - · used - (the space of this folder cannot be measured)'
      : 'Free ' + recMB(d.free) + ' MB · cap ' + recMB(d.cap) + ' MB · used ' +
        recMB1(d.used) + ' MB (' +
        (d.cap ? ((d.used || 0) / d.cap * 100).toFixed(1) : '0.0') + '%)';
    var vids = d.videos || [];
    var key = vids.map(function(v) { return v.name + ':' + v.size; }).join('|');
    // The player lives in a modal now, so rebuilding never touches it. Only
    // rebuild when the list really changed, and keep the scroll position
    // anchored to the selected row so you do not lose your place.
    if (document.fullscreenElement || document.webkitFullscreenElement || key === recListKey) {
      updatePlayButtons();
      return;
    }
    recListKey = key;
    var list = el('recList');
    var keepTop = list.scrollTop;
    var keepRel = null;
    var before = list.querySelectorAll('.vrow');
    for (var i = 0; i < before.length; i++) {
      if (before[i].dataset.name === recPlaying) {
        keepRel = before[i].getBoundingClientRect().top - list.getBoundingClientRect().top + list.scrollTop;
        break;
      }
    }
    list.innerHTML = '';
    if (!vids.length) {
      var p = document.createElement('p');
      p.className = 'hint';
      p.textContent = 'Nothing recorded yet.';
      list.appendChild(p);
      return;
    }
    // Newest first: the one just recorded is the one being looked for.
    var rows = [];
    vids.slice().reverse().forEach(function(v) {
      var row = document.createElement('div');
      row.className = 'vrow';
      row.dataset.name = v.name;
      var name = document.createElement('span');
      name.className = 'vname';
      name.textContent = v.name;
      name.title = v.name;
      var meta = document.createElement('span');
      meta.className = 'vmeta';
      meta.textContent = [recFmtDur(v.seconds), recFmtSize(v.size)].filter(Boolean).join(' · ');
      var play = document.createElement('button');
      play.className = 'secondary';
      play.dataset.play = v.name;
      play.innerHTML = recPlaying === v.name ? ICON_STOP : ICON_PLAY;
      play.title = recPlaying === v.name ? 'Stop' : 'Play';
      play.setAttribute('aria-label', play.title + ' ' + v.name);
      play.onclick = function() {
        if (recPlaying === v.name) { recStopPlayer(); }
        else { recPlay(v.name); }
        recRefresh();
      };
      var dl = document.createElement('a');
      dl.href = '/video/file/' + encodeURIComponent(v.name);
      dl.download = v.name;
      dl.innerHTML = ICON_DOWN;
      dl.title = 'Download';
      dl.setAttribute('aria-label', 'Download ' + v.name);
      var del = document.createElement('button');
      del.className = 'secondary';
      del.innerHTML = ICON_BIN;
      del.title = 'Delete';
      del.setAttribute('aria-label', 'Delete ' + v.name);
      del.onclick = function() {
        if (!confirm('Delete ' + v.name + '?')) { return; }
        del.disabled = true;
        post('/api/record/delete', { name: v.name }).then(function(r) {
          if (r.ok === false) {
            del.disabled = false;
            recMsg(r.error || 'Could not delete the video');
          } else {
            if (recPlaying === v.name) { recStopPlayer(); }
            recRefresh();
          }
        }).catch(function() { del.disabled = false; });
      };
      row.appendChild(name);
      row.appendChild(meta);
      row.appendChild(play);
      row.appendChild(dl);
      row.appendChild(del);
      rows.push(row);
    });
    rows.forEach(function(r) { list.appendChild(r); });
    list.scrollTop = keepTop;
    if (keepRel != null) {
      var after = list.querySelectorAll('.vrow');
      for (var j = 0; j < after.length; j++) {
        if (after[j].dataset.name === recPlaying) {
          list.scrollTop = after[j].getBoundingClientRect().top - list.getBoundingClientRect().top + list.scrollTop - keepRel;
          break;
        }
      }
    }
    updatePlayButtons();
  }).catch(function() {});
}
function recPollTick() { recRefresh(); }
// ---- Motion activation ----------------------------------------------------
// Zones are polygons in normalized coordinates, so they work at any
// resolution with the aspect ratio they were drawn for. The list is polled
// once a second while the pane is open, which both refreshes the zone list
// and moves the live level fill on each slider.
var motionData = null;
var drawing = null;             // points of the zone being drawn
var zoneRowsBuiltFor = -1;

function motionMsg(text) {
  var m = el('motionMsg');
  m.textContent = text || '';
  m.classList.toggle('hidden', !text);
  if (text) {
    clearTimeout(motionMsg.t);
    motionMsg.t = setTimeout(function() { motionMsg(''); }, 8000);
  }
}

function motionTick() {
  if (!(window.S && window.S.admin)) { return; }
  if (el('motionPane').classList.contains('hidden')) { return; }
  fetch('/api/motion/list?since=' + motionSince).then(function(r) { return r.json(); }).then(function(d) {
    motionData = d;
    motionSince = d.now || motionSince;
    var b = el('motionBtn');
    b.disabled = !window.S.logFolder;
    b.textContent = d.enabled ? 'Stop motion' : 'Start motion';
    // Leave a slider alone while it is being dragged: the poll would pull the
    // handle, and the number above it, back to the old configured value.
    if (document.activeElement !== el('s_quiet')) { el('s_quiet').value = d.quiet || 30; }
    el('v_quiet').textContent = d.quiet || 30;
    if (document.activeElement !== el('s_preroll')) { el('s_preroll').value = d.preRoll || 0; }
    el('v_preroll').textContent = d.preRoll || 0;
    var est = d.preRollEstimate || {};
    var hint = el('preRollHint');
    hint.textContent = est.message || '';
    hint.classList.toggle('err', !!est.tooHigh);
    if (document.activeElement !== el('s_level')) {
      el('s_level').value = d.level == null ? 85 : d.level;
      el('v_level').textContent = d.level == null ? 85 : d.level;
    }
    if (document.activeElement !== el('s_grid')) {
      el('s_grid').value = d.grid || 32;
      el('v_grid').textContent = d.grid || 32;
    }
    drawZones();
    buildZoneList(d);
    renderMotionDebug(d.events || []);
  }).catch(function() {});
}

// Activation events from the phone: one line whenever the strongest zone's
// level rises by more than two points. Newest first.
function renderMotionDebug(events) {
  var box = el('motionDebug');
  var last = events.length ? events[events.length - 1].t : 0;
  if (last === motionDbgKey) { return; }
  motionDbgKey = last;
  if (!events.length) {
    box.innerHTML = '<p class="hint">No activations yet.</p>';
    return;
  }
  var out = '';
  for (var i = events.length - 1; i >= 0; i--) {
    var e = events[i];
    var d = new Date(e.t);
    var tm = [d.getHours(), d.getMinutes(), d.getSeconds()].map(function(n) {
      return (n < 10 ? '0' : '') + n;
    }).join(':') + '.' + ('00' + d.getMilliseconds()).slice(-3);
    out += '<div class="dbgline"><span class="hi">' + tm + '</span>  ' +
      e.overall + '%  changed ' + e.changed + '/' + e.cells +
      '  shift ' + e.shift + '  peak ' + e.peak +
      '  biggest ' + e.biggest +
      (e.zones ? '  ' + e.zones : '') + '</div>';
  }
  box.innerHTML = out;
}

function buildZoneList(d) {  var list = el('zoneList');
  var zones = d.zones || [];
  if (zones.length !== zoneRowsBuiltFor) {
    list.innerHTML = '';
    zoneRowsBuiltFor = zones.length;
    if (!zones.length) {
      var p = document.createElement('p');
      p.className = 'hint';
      p.textContent = 'No zones yet.';
      list.appendChild(p);
    }
    zones.forEach(function(z) {
      var row = document.createElement('div');
      row.className = 'zrow';
      // The number in a ring, so it reads as the same thing the overlay puts
      // on the video.
      var name = document.createElement('span');
      name.className = 'zid';
      name.textContent = z.id;
      name.title = 'Zone ' + z.id;
      var slider = document.createElement('input');
      slider.type = 'range';
      slider.min = 0;
      slider.max = 100;
      slider.className = 'zslider';
      slider.setAttribute('aria-label', 'Minimum object size for zone ' + z.id);
      slider.dataset.id = z.id;
      slider.value = z.sensitivity;
      slider.onchange = function() { saveZones(); };
      var del = document.createElement('button');
      del.className = 'secondary';
      del.innerHTML = ICON_BIN;
      del.title = 'Delete';
      del.setAttribute('aria-label', 'Delete zone ' + z.id);
      del.onclick = function() {
        if (!confirm('Delete zone ' + z.id + '?')) { return; }
        var left = ((motionData || {}).zones || [])
          .filter(function(x) { return x.id !== z.id; })
          .map(function(x) { return { sensitivity: x.sensitivity, points: x.points }; });
        saveZones(left);
      };
      row.appendChild(name);
      row.appendChild(slider);
      row.appendChild(del);
      list.appendChild(row);
    });
  }
  // The meters move without a rebuild, so a slider being dragged is not
  // pulled out from under the finger: the fill is the live level, the handle
  // is the sensitivity threshold, and red means the zone is firing.
  zones.forEach(function(z) {
    var s = list.querySelector('input[data-id="' + z.id + '"]');
    if (!s) { return; }
    if (document.activeElement !== s) { s.value = z.sensitivity; }
    // The phone gives the highest level since the previous poll, so a spike
    // between polls still reaches us. Show it immediately, then settle back
    // to the live level quickly, keeping the meter lively.
    var live = z.level || 0;
    var shown = Math.max(live, z.peakSince || 0);
    paintLevel(s, shown);
    clearTimeout(s._decay);
    if (shown > live) {
      s._decay = setTimeout(function() { paintLevel(s, live); }, 700);
    }
  });
  el('zoneClear').classList.toggle('hidden', !zones.length || !!drawing);
}

function paintLevel(s, level) {
  var color = level >= (parseInt(s.value, 10) || 0) ? '#e53935' : '#2b6cb0';
  s.style.background = 'linear-gradient(to right, ' + color + ' 0%, ' + color + ' ' +
    Math.max(level, 2) + '%, #243038 ' + Math.max(level, 2) + '%, #1a1a1a 100%)';
}

// The zone set is sent in the compact form the phone stores:
// "aspect;sens:x,y,x,y,...;sens:...".
function zonesPayload(list) {
  var d = motionData || {};
  var parts = [(d.aspect || 1.7778).toFixed(4)];
  list.forEach(function(z) {
    parts.push((z.sensitivity || 0) + ':' + z.points.map(function(p) {
      return Number(p[0]).toFixed(4) + ',' + Number(p[1]).toFixed(4);
    }).join(','));
  });
  return parts.join(';');
}

function saveZones(list) {
  var d = motionData || { zones: [] };
  // Without an explicit list (a slider change), the sensitivities come from
  // the sliders themselves: the polled copy here is whatever the phone last
  // sent, and writing that back would undo the drag.
  var payload = list || (d.zones || []).map(function(z) {
    var s = document.querySelector('input[data-id="' + z.id + '"]');
    return {
      sensitivity: s ? (parseInt(s.value, 10) || 0) : z.sensitivity,
      points: z.points,
    };
  });
  fetch('/api/motion/zones', {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: zonesPayload(payload),
  }).then(function(r) { return r.json(); }).then(function(res) {
    if (res.ok === false) { motionMsg(res.error || 'Could not save the zones'); }
    motionTick();
  }).catch(function() { motionMsg('No answer from the phone.'); });
}

function updateDrawUi() {
  var d = !!drawing;
  el('zoneAdd').classList.toggle('hidden', d);
  el('zoneClear').classList.toggle('hidden', d);
  el('zoneSave').classList.toggle('hidden', !d);
  el('zoneCancel').classList.toggle('hidden', !d);
  el('zoneSave').disabled = !drawing || drawing.length < 3;
  el('drawHint').classList.toggle('hidden', !d);
}

el('zoneAdd').onclick = function() {
  // A zone is drawn on live video: with streaming off there is nothing to
  // see, so switching it on here is the same as pressing its button.
  if (!(window.S && window.S.streaming)) { setSetting('streaming', 'true'); }
  drawing = [];
  updateDrawUi();
  drawZones();
};
el('zoneCancel').onclick = function() { drawing = null; updateDrawUi(); drawZones(); };
el('zoneClear').onclick = function() {
  var n = ((motionData || {}).zones || []).length;
  if (!n) { return; }
  if (!confirm('Delete all ' + n + ' zone' + (n === 1 ? '' : 's') + '?')) { return; }
  saveZones([]);
};
el('deleteAllVids').onclick = function() {
  var label = recCount ? recCount + ' saved video' + (recCount === 1 ? '' : 's') : 'all saved videos';
  if (!confirm('Delete ' + label + '? This cannot be undone.')) { return; }
  this.disabled = true;
  post('/api/record/delete-all', {}).then(function(r) {
    el('deleteAllVids').disabled = false;
    if (r.ok === false) { recMsg(r.error || 'Could not delete the videos'); }
    recRefresh();
  }).catch(function() { el('deleteAllVids').disabled = false; });
};
el('zoneSave').onclick = function() {
  if (!drawing || drawing.length < 3) { return; }
  var d = motionData || { zones: [] };
  var list = (d.zones || []).map(function(z) {
    return { sensitivity: z.sensitivity, points: z.points };
  });
  list.push({ sensitivity: 50, points: drawing });
  drawing = null;
  updateDrawUi();
  saveZones(list);
};

el('motionBtn').onclick = function() {
  var b = el('motionBtn');
  if (b.disabled) { return; }
  b.disabled = true;
  post('/api/motion/set', { enabled: !((motionData || {}).enabled) }).then(function(r) {
    if (r.ok === false) { motionMsg(r.error || 'Could not change motion'); }
    b.disabled = !window.S.logFolder;
    motionTick();
  }).catch(function() { b.disabled = false; motionMsg('No answer from the phone.'); });
};

el('s_quiet').oninput = function() { el('v_quiet').textContent = this.value; };
el('s_quiet').onchange = function() {
  var v = parseInt(this.value, 10) || 30;
  v = Math.max(5, Math.min(300, v));
  this.value = v;
  el('v_quiet').textContent = v;
  post('/api/motion/quiet', { seconds: v });
};
el('s_preroll').oninput = function() { el('v_preroll').textContent = this.value; };
el('s_preroll').onchange = function() {
  var v = parseInt(this.value, 10) || 0;
  v = Math.max(0, Math.min(30, v));
  this.value = v;
  el('v_preroll').textContent = v;
  post('/api/motion/preroll', { seconds: v });
};
// The trigger level is the movement magnitude a cell must show before it
// counts: higher catches smaller and subtler movement, lower ignores it.
el('s_level').oninput = function() { el('v_level').textContent = this.value; };
el('s_level').onchange = function() {
  var v = parseInt(this.value, 10) || 85;
  v = Math.max(0, Math.min(100, v));
  this.value = v;
  el('v_level').textContent = v;
  post('/api/motion/level', { level: v });
};
// Grid size: the number of cells across the long edge. Smaller cells see
// finer detail (and more noise); the zone sliders scale with the grid.
el('s_grid').oninput = function() { el('v_grid').textContent = this.value; };
el('s_grid').onchange = function() {
  var v = parseInt(this.value, 10) || 32;
  v = Math.max(4, Math.min(160, v));
  this.value = v;
  el('v_grid').textContent = v;
  post('/api/motion/grid', { grid: v });
};

// The zone overlay is an SVG stretched over the picture itself, so drawing
// and panning share one coordinate space.
function placeZones() {
  var s = el('zones');
  var img = el('vid'), c = el('video');
  var r = img.getBoundingClientRect(), cr = c.getBoundingClientRect();
  if (!r.width || !r.height || s.classList.contains('hidden')) { return; }
  s.style.left = (r.left - cr.left) + 'px';
  s.style.top = (r.top - cr.top) + 'px';
  s.style.width = r.width + 'px';
  s.style.height = r.height + 'px';
}

function centroidOf(points) {
  var x = 0, y = 0;
  points.forEach(function(p) { x += p[0]; y += p[1]; });
  return [x / points.length, y / points.length];
}

function drawZones() {
  var s = el('zones');
  var zones = (motionData || {}).zones || [];
  if (!zones.length && !drawing) {
    s.classList.add('hidden');
    s.innerHTML = '';
    return;
  }
  placeZones();
  var out = [];
  zones.forEach(function(z) {
    var pts = z.points.map(function(p) {
      return (p[0] * 1000).toFixed(1) + ',' + (p[1] * 1000).toFixed(1);
    }).join(' ');
    var firing = z.level >= z.sensitivity;
    out.push('<polygon points="' + pts + '" fill="rgba(255,82,82,' +
      (firing ? 0.35 : 0.12) + ')" stroke="' + (firing ? '#ff1744' : '#ff8a80') +
      '" stroke-width="3"/>');
    var c = centroidOf(z.points);
    out.push('<text x="' + (c[0] * 1000).toFixed(0) + '" y="' + (c[1] * 1000).toFixed(0) +
      '" fill="#fff" font-size="60" text-anchor="middle" dominant-baseline="middle">' +
      z.id + '</text>');
  });
  if (drawing && drawing.length) {
    out.push('<polyline points="' + drawing.map(function(p) {
      return (p[0] * 1000).toFixed(1) + ',' + (p[1] * 1000).toFixed(1);
    }).join(' ') + '" fill="none" stroke="#7cc4ff" stroke-width="3"/>');
    drawing.forEach(function(p) {
      out.push('<circle cx="' + (p[0] * 1000).toFixed(1) + '" cy="' + (p[1] * 1000).toFixed(1) +
        '" r="8" fill="#7cc4ff"/>');
    });
  }
  s.innerHTML = out.join('');
  s.classList.remove('hidden');
}

// A click inside the picture adds a point while a zone is being drawn; the
// pan gesture stands down for the duration.
el('video').addEventListener('pointerdown', function(e) {
  if (!drawing) { return; }
  var r = el('vid').getBoundingClientRect();
  if (!r.width) { return; }
  drawing.push([
    Math.round(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * 10000) / 10000,
    Math.round(Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)) * 10000) / 10000,
  ]);
  updateDrawUi();
  drawZones();
  e.preventDefault();
});

// Why a browser may refuse to download, and the one-time way out.
el('dlHelp').onclick = function(e) {
  e.preventDefault();
  el('dlHelpBox').classList.remove('hidden');
};
el('dlHelpClose').onclick = function() { el('dlHelpBox').classList.add('hidden'); };
el('dlHelpBox').onclick = function(e) { if (e.target === this) { this.classList.add('hidden'); } };
el('recPlayerClose').onclick = function() { recStopPlayer(); };
el('recPlayerModal').onclick = function(e) { if (e.target === this) { recStopPlayer(); } };
document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape' && !el('recPlayerModal').classList.contains('hidden')) { recStopPlayer(); }
});
// The trust commands are written with the address this page is actually on,
// so they work as pasted. The certificate itself is fetched with the page's
// own credentials and saved from a blob, which needs no second TLS
// handshake - that second handshake is exactly what Chrome refuses.
(function() {
  var url = location.protocol + '//' + location.host + '/cert';
  el('wCmd').textContent = 'curl -k -o andwebcam.crt ' + url + ' && certutil -addstore -user Root andwebcam.crt';
  el('mCmd').textContent = 'curl -k -o andwebcam.crt ' + url + ' && security add-trusted-cert -r trustRoot andwebcam.crt';
  el('lCmd').textContent = 'curl -k ' + url + ' | certutil -d sql:${'$'}HOME/.pki/nssdb -A -t "C,," -n andwebcam';
})();
el('dlCertBtn').onclick = function() {
  fetch('/cert').then(function(r) { return r.blob(); }).then(function(b) {
    var a = document.createElement('a');
    a.href = URL.createObjectURL(b);
    a.download = 'andwebcam.crt';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function() { URL.revokeObjectURL(a.href); }, 10000);
  }).catch(function() {
    window.location.href = '/cert';
  });
};
el('recBtn').onclick = function() {
  var b = el('recBtn');
  if (b.disabled) { return; }
  b.disabled = true;
  if (window.S && window.S.recording) {
    b.textContent = 'Stopping...';
    post('/api/record/stop', {}).then(function() {
      b.disabled = false;
      // The answer arrives before the file is finalized on the phone; check
      // again once it has been.
      setTimeout(recRefresh, 500);
      setTimeout(recRefresh, 2500);
    }).catch(function() { b.disabled = false; });
  } else {
    b.textContent = 'Starting...';
    post('/api/record/start', {}).then(function(r) {
      b.disabled = false;
      if (r.ok === false) {
        recMsg(r.error || 'Could not start recording');
        b.textContent = 'Record';
      }
      setTimeout(recRefresh, 500);
    }).catch(function() {
      b.disabled = false;
      b.textContent = 'Record';
    });
  }
};
el('s_cap').onchange = function() {
  var p = parseInt(this.value, 10);
  if (!p || p < 1 || p > 100) { this.value = el('s_cap').value || 10; return; }
  post('/api/record/cap', { percent: p }).then(function(r) {
    if (r.ok === false) { recMsg(r.error || 'Could not set the cap'); }
    else { setTimeout(recRefresh, 250); }
  }).catch(function() {});
};
el('recPlay').addEventListener('ended', function() { recPlaying = null; });
el('recPlay').addEventListener('error', function() { recPlaying = null; });
scaleMode = scalePref() || 'fit';
applyScale();
refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>
"""
}
