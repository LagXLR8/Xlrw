// Xlrw account server (Cloudflare Worker + KV).
//
// Accounts:  register / login (PBKDF2 password hashes), one live session per account (a new login on another
//            Minecraft account replaces the old one), avatar upload.
// Plus keys: created only from the admin page (ADMIN_PASSWORD secret). Each key gives N days of Plus, can be
//            redeemed by exactly one account, and can optionally be locked to one username.
// The client receives a signed entitlement (ECDSA P-256) and checks it with the public key built into the mod.

const NAME_RE = /^[A-Za-z0-9_]{3,16}$/;
const DAY = 86400000;
const enc = new TextEncoder();

// ---------- helpers ----------
const cors = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "content-type, authorization, x-mc-uuid, x-xlrw-user, x-admin-password",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};
const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", ...cors } });
const err = (status, error, extra = {}) => json({ ok: false, error, ...extra }, status);

const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const b64url = (buf) => b64(buf).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const rand = (n) => crypto.getRandomValues(new Uint8Array(n));

async function sha256(s) {
  return b64(await crypto.subtle.digest("SHA-256", enc.encode(s)));
}

async function pbkdf2(password, saltB64) {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: unb64(saltB64), iterations: 100000 }, key, 256);
  return b64(bits);
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return diff === 0;
}

async function body(req) {
  try { return await req.json(); } catch { return {}; }
}

const userKey = (name) => "user:" + name.toLowerCase();

// ---------- signing key (created on first use, stored in KV) ----------
async function signingKey(env) {
  let stored = await env.DB.get("signkey", "json");
  if (!stored) {
    const kp = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
    stored = {
      priv: await crypto.subtle.exportKey("jwk", kp.privateKey),
      pub: b64(await crypto.subtle.exportKey("spki", kp.publicKey)),
    };
    await env.DB.put("signkey", JSON.stringify(stored));
  }
  const priv = await crypto.subtle.importKey("jwk", stored.priv, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  return { priv, pub: stored.pub };
}

async function entitlement(env, user, mcUuid) {
  const issued = Date.now();
  const plusUntil = user.plusUntil || 0;
  const payload = `${user.name}|${mcUuid}|${plusUntil}|${issued}`;
  const { priv } = await signingKey(env);
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, priv, enc.encode(payload));
  return { payload, sig: b64(sig), plusUntil, issued };
}

function profile(user) {
  return {
    name: user.name,
    created: user.created,
    plusUntil: user.plusUntil || 0,
    plus: (user.plusUntil || 0) > Date.now(),
    avatarVer: user.avatarVer || 0,
  };
}

// ---------- auth ----------
async function authed(req, env) {
  const h = req.headers.get("authorization") || "";
  const token = h.startsWith("Bearer ") ? h.slice(7) : "";
  const mc = (req.headers.get("x-mc-uuid") || "").toLowerCase();
  const name = (req.headers.get("x-xlrw-user") || "").trim();
  if (!token || !name) return { fail: err(401, "not_logged_in") };
  const user = await env.DB.get(userKey(name), "json");
  if (!user || !user.session) return { fail: err(401, "session", { reason: "expired" }) };
  if (!safeEqual(user.session.tokenHash, await sha256(token))) {
    return { fail: err(401, "session", { reason: "replaced", mcName: user.session.mcName || "" }) };
  }
  if (user.session.mcUuid !== mc) return { fail: err(401, "session", { reason: "other_minecraft" }) };
  return { user, mc };
}

function adminOk(req, env) {
  const p = req.headers.get("x-admin-password") || "";
  return !!env.ADMIN_PASSWORD && safeEqual(p, env.ADMIN_PASSWORD);
}

// ---------- keys ----------
const KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
function newCode() {
  const r = rand(12);
  let s = "";
  for (let i = 0; i < 12; i++) s += KEY_ALPHABET[r[i] % KEY_ALPHABET.length];
  return `XLRW-${s.slice(0, 4)}-${s.slice(4, 8)}-${s.slice(8, 12)}`;
}
const normCode = (c) => String(c || "").toUpperCase().replace(/[^A-Z0-9-]/g, "").trim();

async function listPrefix(env, prefix, limit = 1000) {
  const out = [];
  let cursor;
  do {
    const page = await env.DB.list({ prefix, cursor });
    for (const k of page.keys) {
      const v = await env.DB.get(k.name, "json");
      if (v) out.push(v);
      if (out.length >= limit) return out;
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return out;
}

// ---------- routes ----------
async function route(req, env) {
  const url = new URL(req.url);
  const p = url.pathname;
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });

  if (p === "/" || p === "/health") return json({ ok: true, service: "xlrw-api" });
  if (p === "/admin") return new Response(ADMIN_HTML, { headers: { "content-type": "text/html; charset=utf-8" } });

  if (p === "/api/pubkey") return json({ ok: true, pub: (await signingKey(env)).pub });

  if (p === "/api/register" && req.method === "POST") {
    const { username, password } = await body(req);
    if (!NAME_RE.test(username || "")) return err(400, "bad_username");
    if (typeof password !== "string" || password.length < 6 || password.length > 128) return err(400, "bad_password");
    if (await env.DB.get(userKey(username))) return err(409, "taken");
    const salt = b64(rand(16));
    const user = { name: username, salt, hash: await pbkdf2(password, salt), created: Date.now(), plusUntil: 0, avatarVer: 0, session: null };
    await env.DB.put(userKey(username), JSON.stringify(user));
    return json({ ok: true });
  }

  if (p === "/api/login" && req.method === "POST") {
    const { username, password, mcUuid, mcName } = await body(req);
    if (!NAME_RE.test(username || "") || typeof password !== "string") return err(400, "bad_login");
    const user = await env.DB.get(userKey(username), "json");
    if (!user || !safeEqual(user.hash, await pbkdf2(password, user.salt))) return err(401, "wrong_login");
    const mc = String(mcUuid || "").toLowerCase();
    if (!mc) return err(400, "no_minecraft");
    const token = b64url(rand(32));
    const previous = user.session && user.session.mcUuid !== mc ? (user.session.mcName || "?") : null;
    user.session = { tokenHash: await sha256(token), mcUuid: mc, mcName: String(mcName || "").slice(0, 32), at: Date.now() };
    await env.DB.put(userKey(user.name), JSON.stringify(user));
    return json({ ok: true, token, profile: profile(user), entitlement: await entitlement(env, user, mc), kicked: previous });
  }

  if (p === "/api/me") {
    const a = await authed(req, env);
    if (a.fail) return a.fail;
    return json({ ok: true, profile: profile(a.user), entitlement: await entitlement(env, a.user, a.mc) });
  }

  if (p === "/api/logout" && req.method === "POST") {
    const a = await authed(req, env);
    if (a.fail) return json({ ok: true });
    a.user.session = null;
    await env.DB.put(userKey(a.user.name), JSON.stringify(a.user));
    return json({ ok: true });
  }

  if (p === "/api/avatar" && req.method === "POST") {
    const a = await authed(req, env);
    if (a.fail) return a.fail;
    const { png } = await body(req);
    let bytes;
    try { bytes = unb64(png || ""); } catch { return err(400, "bad_image"); }
    const isPng = bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
    if (!isPng) return err(400, "bad_image");
    if (bytes.length > 96 * 1024) return err(413, "image_too_big");
    await env.DB.put("avatar:" + a.user.name.toLowerCase(), png);
    a.user.avatarVer = (a.user.avatarVer || 0) + 1;
    await env.DB.put(userKey(a.user.name), JSON.stringify(a.user));
    return json({ ok: true, profile: profile(a.user) });
  }

  if (p.startsWith("/api/avatar/")) {
    const name = decodeURIComponent(p.slice("/api/avatar/".length));
    const png = NAME_RE.test(name) ? await env.DB.get("avatar:" + name.toLowerCase()) : null;
    if (!png) return new Response("not found", { status: 404, headers: cors });
    return new Response(unb64(png), { headers: { "content-type": "image/png", "cache-control": "public, max-age=60", ...cors } });
  }

  if (p === "/api/redeem" && req.method === "POST") {
    const a = await authed(req, env);
    if (a.fail) return a.fail;
    const code = normCode((await body(req)).key);
    const k = code ? await env.DB.get("key:" + code, "json") : null;
    if (!k || k.revoked) return err(404, "bad_key");
    if (k.redeemedBy) return err(409, k.redeemedBy.toLowerCase() === a.user.name.toLowerCase() ? "already_yours" : "used");
    if (k.forUser && k.forUser.toLowerCase() !== a.user.name.toLowerCase()) return err(403, "other_user");
    if (k.expiresAt && Date.now() > k.expiresAt) return err(410, "key_expired");
    k.redeemedBy = a.user.name;
    k.redeemedAt = Date.now();
    await env.DB.put("key:" + code, JSON.stringify(k));
    a.user.plusUntil = Math.max(Date.now(), a.user.plusUntil || 0) + k.days * DAY;
    await env.DB.put(userKey(a.user.name), JSON.stringify(a.user));
    return json({ ok: true, days: k.days, profile: profile(a.user), entitlement: await entitlement(env, a.user, a.mc) });
  }

  // ----- admin -----
  if (p.startsWith("/api/admin/")) {
    if (!adminOk(req, env)) return err(403, "admin_only");
    const b = req.method === "POST" ? await body(req) : {};
    if (p === "/api/admin/keys/create") {
      const days = Math.max(1, Math.min(3650, parseInt(b.days, 10) || 30));
      const count = Math.max(1, Math.min(50, parseInt(b.count, 10) || 1));
      const forUser = b.forUser && NAME_RE.test(b.forUser) ? b.forUser : null;
      const validDays = parseInt(b.validDays, 10) || 0; // 0 = the key itself never expires before use
      const codes = [];
      for (let i = 0; i < count; i++) {
        const code = newCode();
        const k = { code, days, forUser, created: Date.now(), expiresAt: validDays > 0 ? Date.now() + validDays * DAY : 0,
                    note: String(b.note || "").slice(0, 80), redeemedBy: null, redeemedAt: 0, revoked: false };
        await env.DB.put("key:" + code, JSON.stringify(k));
        codes.push(code);
      }
      return json({ ok: true, codes });
    }
    if (p === "/api/admin/keys/list") {
      const keys = await listPrefix(env, "key:");
      keys.sort((x, y) => y.created - x.created);
      return json({ ok: true, keys });
    }
    if (p === "/api/admin/keys/revoke") {
      const code = normCode(b.code);
      const k = await env.DB.get("key:" + code, "json");
      if (!k) return err(404, "bad_key");
      k.revoked = true;
      await env.DB.put("key:" + code, JSON.stringify(k));
      return json({ ok: true });
    }
    if (p === "/api/admin/users/list") {
      const users = (await listPrefix(env, "user:")).map((u) => ({
        name: u.name, created: u.created, plusUntil: u.plusUntil || 0,
        mcName: u.session ? u.session.mcName : "", lastLogin: u.session ? u.session.at : 0,
      }));
      users.sort((x, y) => (y.lastLogin || 0) - (x.lastLogin || 0));
      return json({ ok: true, users });
    }
    if (p === "/api/admin/users/plus") {
      const user = await env.DB.get(userKey(String(b.username || "")), "json");
      if (!user) return err(404, "no_user");
      const days = parseInt(b.days, 10) || 0;
      user.plusUntil = days <= 0 ? 0 : Math.max(Date.now(), user.plusUntil || 0) + days * DAY;
      await env.DB.put(userKey(user.name), JSON.stringify(user));
      return json({ ok: true, plusUntil: user.plusUntil });
    }
    if (p === "/api/admin/users/kick") {
      const user = await env.DB.get(userKey(String(b.username || "")), "json");
      if (!user) return err(404, "no_user");
      user.session = null;
      await env.DB.put(userKey(user.name), JSON.stringify(user));
      return json({ ok: true });
    }
    return err(404, "not_found");
  }

  return err(404, "not_found");
}

export default {
  async fetch(req, env) {
    try {
      return await route(req, env);
    } catch (e) {
      return err(500, "server_error", { detail: String(e && e.message || e) });
    }
  },
};

// ---------- admin page ----------
const ADMIN_HTML = `<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Xlrw Admin</title>
<style>
:root{--bg:#16161a;--panel:#1f2026;--line:#33343c;--text:#e8d8c9;--dim:#8d97ab;--acc:#f3701e;--ok:#5fd38d;--bad:#e0403a}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,Segoe UI,sans-serif}
main{max-width:880px;margin:0 auto;padding:24px 16px}
h1{font-size:22px;margin:0 0 4px;letter-spacing:.04em}h1 b{color:var(--acc)}
p.sub{color:var(--dim);margin:0 0 20px}
section{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px;margin-bottom:16px}
h2{font-size:15px;margin:0 0 12px;color:var(--acc);text-transform:uppercase;letter-spacing:.08em}
label{display:block;color:var(--dim);font-size:13px;margin-bottom:4px}
input{width:100%;background:#121216;border:1px solid var(--line);color:var(--text);border-radius:6px;padding:9px 10px;font:inherit}
.row{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:12px}
button{background:var(--acc);color:#141416;border:0;border-radius:6px;padding:9px 16px;font:600 14px system-ui;cursor:pointer}
button.ghost{background:transparent;color:var(--text);border:1px solid var(--line)}
pre{background:#121216;border:1px solid var(--line);border-radius:6px;padding:10px;white-space:pre-wrap;word-break:break-all;margin:12px 0 0}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line)}
th{color:var(--dim);font-weight:500}.ok{color:var(--ok)}.bad{color:var(--bad)}.dim{color:var(--dim)}
.wrap{overflow-x:auto}#msg{min-height:1.5em;color:var(--dim)}
</style></head><body><main>
<h1><b>XLRW</b> ADMIN</h1><p class="sub">Tạo key Plus, xem tài khoản. Mật khẩu chỉ lưu trong tab này.</p>
<section><h2>Đăng nhập quản trị</h2>
<div class="row"><div><label>Mật khẩu admin</label><input id="pw" type="password" autocomplete="current-password"></div></div>
<button onclick="refresh()">Vào</button> <span id="msg"></span></section>
<section><h2>Tạo key</h2>
<div class="row">
<div><label>Số ngày Plus</label><input id="days" type="number" value="30" min="1"></div>
<div><label>Số lượng key</label><input id="count" type="number" value="1" min="1" max="50"></div>
<div><label>Chỉ cho tên (để trống = ai cũng được)</label><input id="forUser" placeholder="vd LagXLR8"></div>
<div><label>Key hết hạn nếu chưa dùng sau (ngày, 0 = không)</label><input id="validDays" type="number" value="0" min="0"></div>
</div>
<div class="row"><div><label>Ghi chú</label><input id="note" placeholder="vd bán cho Minh"></div></div>
<button onclick="createKeys()">Tạo key</button><pre id="newKeys" hidden></pre></section>
<section><h2>Key đã tạo</h2><div class="wrap"><table id="keys"></table></div></section>
<section><h2>Tài khoản</h2><div class="wrap"><table id="users"></table></div></section>
</main><script>
const $=id=>document.getElementById(id);
const fmt=t=>t?new Date(t).toLocaleString('vi-VN'):'—';
const esc=s=>String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
async function api(path,data){const r=await fetch(path,{method:'POST',headers:{'content-type':'application/json','x-admin-password':$('pw').value},body:JSON.stringify(data||{})});const j=await r.json().catch(()=>({ok:false,error:'bad_response'}));if(!j.ok)throw new Error(j.error||r.status);return j}
async function refresh(){try{$('msg').textContent='Đang tải...';const k=await api('/api/admin/keys/list');const u=await api('/api/admin/users/list');
$('keys').innerHTML='<tr><th>Key</th><th>Ngày</th><th>Cho</th><th>Trạng thái</th><th>Tạo</th><th></th></tr>'+k.keys.map(x=>'<tr><td><code>'+esc(x.code)+'</code><div class="dim">'+esc(x.note)+'</div></td><td>'+x.days+'</td><td>'+esc(x.forUser||'ai cũng được')+'</td><td>'+(x.revoked?'<span class="bad">đã huỷ</span>':x.redeemedBy?'<span class="ok">'+esc(x.redeemedBy)+'</span> '+fmt(x.redeemedAt):(x.expiresAt&&Date.now()>x.expiresAt?'<span class="bad">hết hạn</span>':'chưa dùng'))+'</td><td>'+fmt(x.created)+'</td><td>'+(x.revoked?'':'<button class="ghost" onclick="revoke(\\''+x.code+'\\')">Huỷ</button>')+'</td></tr>').join('');
$('users').innerHTML='<tr><th>Tên</th><th>Plus đến</th><th>Minecraft</th><th>Đăng nhập</th><th></th></tr>'+u.users.map(x=>'<tr><td>'+esc(x.name)+'</td><td>'+(x.plusUntil>Date.now()?'<span class="ok">'+fmt(x.plusUntil)+'</span>':'<span class="dim">không</span>')+'</td><td>'+esc(x.mcName||'—')+'</td><td>'+fmt(x.lastLogin)+'</td><td><button class="ghost" onclick="plus(\\''+x.name+'\\')">+/− Plus</button> <button class="ghost" onclick="kick(\\''+x.name+'\\')">Đăng xuất</button></td></tr>').join('');
$('msg').textContent='Đã tải '+k.keys.length+' key, '+u.users.length+' tài khoản.'}catch(e){$('msg').textContent=e.message==='admin_only'?'Sai mật khẩu admin':'Lỗi: '+e.message}}
async function createKeys(){try{const j=await api('/api/admin/keys/create',{days:$('days').value,count:$('count').value,forUser:$('forUser').value.trim(),validDays:$('validDays').value,note:$('note').value});$('newKeys').hidden=false;$('newKeys').textContent=j.codes.join('\\n');refresh()}catch(e){$('msg').textContent='Lỗi: '+e.message}}
async function revoke(code){if(!confirm('Huỷ key '+code+'?'))return;await api('/api/admin/keys/revoke',{code});refresh()}
async function kick(username){await api('/api/admin/users/kick',{username});refresh()}
async function plus(username){const d=prompt('Thêm bao nhiêu ngày Plus cho '+username+'? (0 = gỡ Plus)','30');if(d===null)return;await api('/api/admin/users/plus',{username,days:d});refresh()}
</script></body></html>`;
