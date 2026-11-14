//! Liaison minimale avec libmpv (chargée dynamiquement : `libmpv-2.dll` à côté de
//! l'exécutable). L'image est rendue par l'API de rendu OpenGL de mpv dans une texture, puis
//! copiée dans la zone vidéo de la fenêtre egui.

use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::sync::Arc;

use egui_glow::glow::{self, HasContext};

const MPV_FORMAT_STRING: c_int = 1;
pub const MPV_EVENT_NONE: c_int = 0;
pub const MPV_EVENT_SHUTDOWN: c_int = 1;
pub const MPV_EVENT_START_FILE: c_int = 6;
pub const MPV_EVENT_END_FILE: c_int = 7;
pub const MPV_EVENT_FILE_LOADED: c_int = 8;
pub const MPV_EVENT_PLAYBACK_RESTART: c_int = 21;

pub const END_REASON_EOF: c_int = 0;
pub const END_REASON_ERROR: c_int = 4;

pub const MPV_ERROR_LOADING_FAILED: c_int = -13;
pub const MPV_ERROR_NOTHING_TO_PLAY: c_int = -16;
pub const MPV_ERROR_UNKNOWN_FORMAT: c_int = -17;
pub const MPV_ERROR_AO_INIT_FAILED: c_int = -14;
pub const MPV_ERROR_VO_INIT_FAILED: c_int = -15;

const RENDER_PARAM_INVALID: c_int = 0;
const RENDER_PARAM_API_TYPE: c_int = 1;
const RENDER_PARAM_OPENGL_INIT_PARAMS: c_int = 2;
const RENDER_PARAM_OPENGL_FBO: c_int = 3;
const RENDER_PARAM_FLIP_Y: c_int = 4;
const RENDER_PARAM_ADVANCED_CONTROL: c_int = 10;
const RENDER_PARAM_SW_SIZE: c_int = 17;
const RENDER_PARAM_SW_FORMAT: c_int = 18;
const RENDER_PARAM_SW_STRIDE: c_int = 19;
const RENDER_PARAM_SW_POINTER: c_int = 20;
const RENDER_UPDATE_FRAME: u64 = 1;

#[repr(C)]
struct MpvEvent {
    event_id: c_int,
    error: c_int,
    reply_userdata: u64,
    data: *mut c_void,
}

#[repr(C)]
struct MpvEventEndFile {
    reason: c_int,
    error: c_int,
    playlist_entry_id: i64,
    playlist_insert_id: i64,
    playlist_insert_num_entries: c_int,
}

#[repr(C)]
struct RenderParam {
    kind: c_int,
    data: *mut c_void,
}

#[repr(C)]
struct OpenGlInitParams {
    get_proc_address: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void,
    get_proc_address_ctx: *mut c_void,
}

#[repr(C)]
struct OpenGlFbo {
    fbo: c_int,
    w: c_int,
    h: c_int,
    internal_format: c_int,
}

type Handle = *mut c_void;

struct Api {
    _lib: libloading::Library,
    create: unsafe extern "C" fn() -> Handle,
    initialize: unsafe extern "C" fn(Handle) -> c_int,
    terminate_destroy: unsafe extern "C" fn(Handle),
    set_option_string: unsafe extern "C" fn(Handle, *const c_char, *const c_char) -> c_int,
    set_property_string: unsafe extern "C" fn(Handle, *const c_char, *const c_char) -> c_int,
    get_property: unsafe extern "C" fn(Handle, *const c_char, c_int, *mut c_void) -> c_int,
    free: unsafe extern "C" fn(*mut c_void),
    command: unsafe extern "C" fn(Handle, *const *const c_char) -> c_int,
    command_async: unsafe extern "C" fn(Handle, u64, *const *const c_char) -> c_int,
    wait_event: unsafe extern "C" fn(Handle, f64) -> *mut MpvEvent,
    set_wakeup_callback: unsafe extern "C" fn(Handle, Option<unsafe extern "C" fn(*mut c_void)>, *mut c_void),
    error_string: unsafe extern "C" fn(c_int) -> *const c_char,
    render_create: unsafe extern "C" fn(*mut *mut c_void, Handle, *mut RenderParam) -> c_int,
    render_free: unsafe extern "C" fn(*mut c_void),
    render_render: unsafe extern "C" fn(*mut c_void, *mut RenderParam) -> c_int,
    render_set_update_callback: unsafe extern "C" fn(*mut c_void, Option<unsafe extern "C" fn(*mut c_void)>, *mut c_void),
    render_update: unsafe extern "C" fn(*mut c_void) -> u64,
    render_report_swap: unsafe extern "C" fn(*mut c_void),
}

impl Api {
    fn load() -> Result<Api, String> {
        let exe_dir = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_path_buf()));
        let names = ["libmpv-2.dll", "mpv-2.dll", "mpv-1.dll", "libmpv.so.2", "libmpv.so", "libmpv.dylib"];
        let mut last_err = String::new();
        for name in names {
            let candidates = exe_dir.iter().map(|d| d.join(name)).chain(std::iter::once(name.into()));
            for path in candidates {
                // SAFETY: chargement d'une bibliothèque connue ; ses fonctions sont typées ci-dessous.
                match unsafe { libloading::Library::new(&path) } {
                    Ok(lib) => return Self::bind(lib),
                    Err(e) => last_err = e.to_string(),
                }
            }
        }
        Err(format!("libmpv introuvable ({last_err})"))
    }

    fn bind(lib: libloading::Library) -> Result<Api, String> {
        macro_rules! sym {
            ($name:literal) => {
                // SAFETY: signature identique à celle de client.h / render.h.
                *unsafe { lib.get(concat!($name, "\0").as_bytes()) }.map_err(|_| format!("libmpv : {} absent", $name))?
            };
        }
        Ok(Api {
            create: sym!("mpv_create"),
            initialize: sym!("mpv_initialize"),
            terminate_destroy: sym!("mpv_terminate_destroy"),
            set_option_string: sym!("mpv_set_option_string"),
            set_property_string: sym!("mpv_set_property_string"),
            get_property: sym!("mpv_get_property"),
            free: sym!("mpv_free"),
            command: sym!("mpv_command"),
            command_async: sym!("mpv_command_async"),
            wait_event: sym!("mpv_wait_event"),
            set_wakeup_callback: sym!("mpv_set_wakeup_callback"),
            error_string: sym!("mpv_error_string"),
            render_create: sym!("mpv_render_context_create"),
            render_free: sym!("mpv_render_context_free"),
            render_render: sym!("mpv_render_context_render"),
            render_set_update_callback: sym!("mpv_render_context_set_update_callback"),
            render_update: sym!("mpv_render_context_update"),
            render_report_swap: sym!("mpv_render_context_report_swap"),
            _lib: lib,
        })
    }
}

/// Évènement mpv utile à l'application.
#[derive(Debug, Clone)]
pub enum Event {
    StartFile,
    FileLoaded,
    PlaybackRestart,
    EndFile { reason: i32, error: i32 },
    Shutdown,
}

/// Instance mpv (thread-safe côté client API).
pub struct Mpv {
    api: Arc<Api>,
    handle: Handle,
}

// SAFETY: l'API cliente de mpv est utilisable depuis plusieurs threads.
unsafe impl Send for Mpv {}
unsafe impl Sync for Mpv {}

fn cstr(s: &str) -> CString {
    CString::new(s.replace('\0', "")).unwrap_or_default()
}

impl Mpv {
    /// Crée et initialise mpv pour un rendu dans l'application (`vo=libmpv`).
    pub fn new(wakeup: Box<dyn Fn() + Send + Sync>) -> Result<Arc<Mpv>, String> {
        let api = Arc::new(Api::load()?);
        // SAFETY: appels conformes à l'API ; le handle est détruit dans Drop.
        let handle = unsafe { (api.create)() };
        if handle.is_null() {
            return Err("mpv_create a échoué".into());
        }
        let mpv = Mpv { api, handle };
        let options = [
            ("vo", "libmpv"),
            ("hwdec", "auto-safe"),
            ("idle", "yes"),
            ("keep-open", "no"),
            ("terminal", "no"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("osc", "no"),
            ("ytdl", "no"),
            ("config", "no"),
            ("load-scripts", "no"),
            ("user-agent", crate::xtream::USER_AGENT),
            ("network-timeout", "20"),
            ("cache", "yes"),
            ("demuxer-max-bytes", "64MiB"),
            ("demuxer-readahead-secs", "20"),
            ("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=4"),
            ("audio-client-name", "IPTV Player"),
            ("alang", "fr,fre,fra,fr-FR"),
            // Sous-titres désactivés par défaut (comme sur Android) : les flux issus de la TNT ou
            // du satellite portent souvent une piste télétexte / DVB que mpv activerait d'office,
            // affichée comme un bloc noir opaque au milieu de l'image. Activables par le menu 💬.
            ("sid", "no"),
            ("sub-ass-override", "yes"),
            ("osd-level", "0"),
        ];
        for (k, v) in options {
            mpv.set_option(k, v);
        }
        // Diagnostic : journal de mpv dans un fichier (les URL y figurent, avec les identifiants).
        if let Ok(path) = std::env::var("IPTV_MPV_LOG") {
            mpv.set_option("log-file", &path);
        }
        // SAFETY: handle valide.
        let rc = unsafe { (mpv.api.initialize)(mpv.handle) };
        if rc < 0 {
            return Err(format!("mpv_initialize : {}", mpv.error_string(rc)));
        }
        // Rappel appelé par mpv (depuis ses threads) quand un évènement est disponible.
        let boxed: Box<Box<dyn Fn() + Send + Sync>> = Box::new(wakeup);
        let ptr = Box::into_raw(boxed) as *mut c_void;
        unsafe extern "C" fn on_wakeup(d: *mut c_void) {
            // SAFETY: `d` pointe vers la Box fuitée ci-dessus, valide pour toute la vie du processus.
            let f = unsafe { &*(d as *const Box<dyn Fn() + Send + Sync>) };
            f();
        }
        // SAFETY: le pointeur reste valide (jamais libéré : une seule instance par processus).
        unsafe { (mpv.api.set_wakeup_callback)(mpv.handle, Some(on_wakeup), ptr) };
        Ok(Arc::new(mpv))
    }

    pub fn error_string(&self, code: i32) -> String {
        // SAFETY: mpv renvoie une chaîne statique.
        unsafe { CStr::from_ptr((self.api.error_string)(code)) }.to_string_lossy().into_owned()
    }

    pub fn set_option(&self, name: &str, value: &str) {
        let (n, v) = (cstr(name), cstr(value));
        // SAFETY: chaînes C valides pendant l'appel.
        unsafe { (self.api.set_option_string)(self.handle, n.as_ptr(), v.as_ptr()) };
    }

    pub fn set(&self, name: &str, value: &str) {
        let (n, v) = (cstr(name), cstr(value));
        // SAFETY: idem.
        unsafe { (self.api.set_property_string)(self.handle, n.as_ptr(), v.as_ptr()) };
    }

    pub fn get(&self, name: &str) -> Option<String> {
        let n = cstr(name);
        let mut out: *mut c_char = std::ptr::null_mut();
        // SAFETY: format chaîne : mpv alloue la chaîne, libérée avec mpv_free.
        let rc = unsafe { (self.api.get_property)(self.handle, n.as_ptr(), MPV_FORMAT_STRING, &mut out as *mut _ as *mut c_void) };
        if rc < 0 || out.is_null() {
            return None;
        }
        let s = unsafe { CStr::from_ptr(out) }.to_string_lossy().into_owned();
        unsafe { (self.api.free)(out as *mut c_void) };
        Some(s)
    }

    pub fn get_f64(&self, name: &str) -> Option<f64> {
        self.get(name).and_then(|v| v.parse().ok())
    }

    pub fn get_bool(&self, name: &str) -> Option<bool> {
        self.get(name).map(|v| v == "yes")
    }

    pub fn command(&self, args: &[&str]) -> i32 {
        let owned: Vec<CString> = args.iter().map(|a| cstr(a)).collect();
        let mut ptrs: Vec<*const c_char> = owned.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(std::ptr::null());
        // SAFETY: tableau terminé par NULL, chaînes valides pendant l'appel.
        unsafe { (self.api.command)(self.handle, ptrs.as_ptr()) }
    }

    /// Commande non bloquante (chargement d'un flux réseau).
    pub fn command_async(&self, args: &[&str]) -> i32 {
        let owned: Vec<CString> = args.iter().map(|a| cstr(a)).collect();
        let mut ptrs: Vec<*const c_char> = owned.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(std::ptr::null());
        // SAFETY: mpv copie les arguments avant de rendre la main.
        unsafe { (self.api.command_async)(self.handle, 0, ptrs.as_ptr()) }
    }

    /// Évènements en attente (non bloquant).
    pub fn poll_events(&self) -> Vec<Event> {
        let mut out = Vec::new();
        loop {
            // SAFETY: l'évènement retourné reste valide jusqu'au prochain appel.
            let ev = unsafe { &*(self.api.wait_event)(self.handle, 0.0) };
            match ev.event_id {
                MPV_EVENT_NONE => break,
                MPV_EVENT_START_FILE => out.push(Event::StartFile),
                MPV_EVENT_FILE_LOADED => out.push(Event::FileLoaded),
                MPV_EVENT_PLAYBACK_RESTART => out.push(Event::PlaybackRestart),
                MPV_EVENT_END_FILE if !ev.data.is_null() => {
                    let d = unsafe { &*(ev.data as *const MpvEventEndFile) };
                    out.push(Event::EndFile { reason: d.reason, error: d.error });
                }
                MPV_EVENT_SHUTDOWN => {
                    out.push(Event::Shutdown);
                    break;
                }
                _ => {}
            }
        }
        out
    }
}

impl Drop for Mpv {
    fn drop(&mut self) {
        // SAFETY: le contexte de rendu a été libéré avant (voir VideoRenderer::destroy).
        unsafe { (self.api.terminate_destroy)(self.handle) };
    }
}

/// Rendu de la vidéo dans la fenêtre egui, par l'API de rendu de mpv :
/// - OpenGL quand libmpv le permet (image rendue directement dans une texture) ;
/// - sinon rendu logiciel (builds ARM64 de libmpv sans OpenGL) : image calculée par mpv en
///   mémoire, envoyée dans une texture, puis recopiée dans la zone vidéo.
///
/// N'est utilisé que sur le thread principal, contexte OpenGL d'egui actif.
pub struct VideoRenderer {
    mpv: Arc<Mpv>,
    ctx: *mut c_void,
    software: bool,
    fbo: Option<glow::Framebuffer>,
    texture: Option<glow::Texture>,
    /// Taille de la texture (pixels).
    size: (i32, i32),
    /// Tampon du rendu logiciel (aligné sur 64 octets) et largeur de ligne en octets.
    buffer: Vec<Align64>,
    stride: usize,
    has_frame: bool,
    failed: bool,
    /// Rappels conservés en vie tant que le contexte existe.
    _proc: Option<Box<GetProcAddress>>,
    update: Box<Box<dyn Fn() + Send + Sync>>,
}

#[derive(Clone, Copy)]
#[repr(C, align(64))]
struct Align64([u8; 64]);

type GetProcAddress = Arc<dyn Fn(&CStr) -> *const c_void + Send + Sync>;

/// Taille maximale du rendu logiciel (agrandi ensuite par la carte graphique).
const SW_MAX: (i32, i32) = (1920, 1080);

// SAFETY: utilisé uniquement depuis le thread de rendu (rappel de peinture egui).
unsafe impl Send for VideoRenderer {}

impl VideoRenderer {
    /// `on_frame` est appelé (depuis un thread de mpv) quand une nouvelle image est prête.
    pub fn new(mpv: Arc<Mpv>, on_frame: Box<dyn Fn() + Send + Sync>) -> Self {
        Self {
            mpv,
            ctx: std::ptr::null_mut(),
            software: false,
            fbo: None,
            texture: None,
            size: (0, 0),
            buffer: Vec::new(),
            stride: 0,
            has_frame: false,
            failed: false,
            _proc: None,
            update: Box::new(on_frame),
        }
    }

    /// Crée le contexte de rendu (contexte OpenGL actif). À faire avant tout chargement :
    /// sans lui, mpv refuse la sortie vidéo.
    pub fn ensure_context(&mut self, get_proc: &GetProcAddress) -> bool {
        if !self.ctx.is_null() {
            return true;
        }
        if self.failed {
            return false;
        }
        unsafe extern "C" fn proc_address(ctx: *mut c_void, name: *const c_char) -> *mut c_void {
            // SAFETY: `ctx` pointe vers la Box conservée dans `_proc`.
            let f = unsafe { &*(ctx as *const GetProcAddress) };
            f(unsafe { CStr::from_ptr(name) }) as *mut c_void
        }
        let proc_box = Box::new(get_proc.clone());
        let mut ctx: *mut c_void = std::ptr::null_mut();
        let mut advanced: c_int = 0;

        // 1. OpenGL
        let mut init = OpenGlInitParams { get_proc_address: proc_address, get_proc_address_ctx: &*proc_box as *const _ as *mut c_void };
        let gl_type = c"opengl";
        let mut params = [
            RenderParam { kind: RENDER_PARAM_API_TYPE, data: gl_type.as_ptr() as *mut c_void },
            RenderParam { kind: RENDER_PARAM_OPENGL_INIT_PARAMS, data: &mut init as *mut _ as *mut c_void },
            RenderParam { kind: RENDER_PARAM_ADVANCED_CONTROL, data: &mut advanced as *mut _ as *mut c_void },
            RenderParam { kind: RENDER_PARAM_INVALID, data: std::ptr::null_mut() },
        ];
        // SAFETY: paramètres valides pendant l'appel ; le contexte OpenGL est actif.
        let rc = unsafe { (self.mpv.api.render_create)(&mut ctx, self.mpv.handle, params.as_mut_ptr()) };
        if rc < 0 || ctx.is_null() {
            // 2. Rendu logiciel
            ctx = std::ptr::null_mut();
            let sw_type = c"sw";
            let mut params = [
                RenderParam { kind: RENDER_PARAM_API_TYPE, data: sw_type.as_ptr() as *mut c_void },
                RenderParam { kind: RENDER_PARAM_ADVANCED_CONTROL, data: &mut advanced as *mut _ as *mut c_void },
                RenderParam { kind: RENDER_PARAM_INVALID, data: std::ptr::null_mut() },
            ];
            // SAFETY: idem.
            let rc = unsafe { (self.mpv.api.render_create)(&mut ctx, self.mpv.handle, params.as_mut_ptr()) };
            if rc < 0 || ctx.is_null() {
                self.failed = true;
                return false;
            }
            self.software = true;
        }
        self.ctx = ctx;
        self._proc = Some(proc_box);
        unsafe extern "C" fn cb(d: *mut c_void) {
            // SAFETY: `d` pointe vers la Box `update`, qui vit aussi longtemps que `self`.
            let f = unsafe { &*(d as *const Box<dyn Fn() + Send + Sync>) };
            f();
        }
        // SAFETY: rappel retiré (render_free) avant la libération de `update`.
        unsafe { (self.mpv.api.render_set_update_callback)(self.ctx, Some(cb), &*self.update as *const _ as *mut c_void) };
        true
    }

    /// (Re)crée la texture et son framebuffer à la taille voulue.
    unsafe fn ensure_target(&mut self, gl: &glow::Context, w: i32, h: i32) -> bool {
        if self.size == (w, h) && self.texture.is_some() {
            return true;
        }
        // SAFETY: contexte OpenGL actif (appelant).
        unsafe {
            if let Some(t) = self.texture.take() {
                gl.delete_texture(t);
            }
            if let Some(f) = self.fbo.take() {
                gl.delete_framebuffer(f);
            }
            let Ok(tex) = gl.create_texture() else { return false };
            gl.bind_texture(glow::TEXTURE_2D, Some(tex));
            gl.tex_image_2d(glow::TEXTURE_2D, 0, glow::RGBA8 as i32, w, h, 0, glow::RGBA, glow::UNSIGNED_BYTE, glow::PixelUnpackData::Slice(None));
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, glow::LINEAR as i32);
            gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, glow::LINEAR as i32);
            gl.bind_texture(glow::TEXTURE_2D, None);
            let previous = gl.get_parameter_framebuffer(glow::FRAMEBUFFER_BINDING);
            let Ok(fbo) = gl.create_framebuffer() else { return false };
            gl.bind_framebuffer(glow::FRAMEBUFFER, Some(fbo));
            gl.framebuffer_texture_2d(glow::FRAMEBUFFER, glow::COLOR_ATTACHMENT0, glow::TEXTURE_2D, Some(tex), 0);
            gl.bind_framebuffer(glow::FRAMEBUFFER, previous);
            self.texture = Some(tex);
            self.fbo = Some(fbo);
        }
        self.size = (w, h);
        self.has_frame = false;
        true
    }

    /// Rend l'image courante dans la zone `(x, y, w, h)` (pixels, origine en bas à gauche).
    pub fn paint(&mut self, gl: &glow::Context, get_proc: &GetProcAddress, rect: (i32, i32, i32, i32)) {
        let (x, y, w, h) = rect;
        if w <= 0 || h <= 0 || !self.ensure_context(get_proc) {
            return;
        }
        // Taille de rendu : la zone vidéo, réduite en rendu logiciel (mêmes proportions).
        let (rw, rh) = if self.software {
            let scale = (SW_MAX.0 as f32 / w as f32).min(SW_MAX.1 as f32 / h as f32).min(1.0);
            (((w as f32 * scale) as i32).max(2) & !1, ((h as f32 * scale) as i32).max(2) & !1)
        } else {
            (w, h)
        };
        // SAFETY: appels OpenGL dans le contexte actif d'egui ; l'état est restauré par egui_glow.
        unsafe {
            if !self.ensure_target(gl, rw, rh) {
                return;
            }
            let (Some(fbo), Some(tex)) = (self.fbo, self.texture) else { return };
            let previous_fbo = gl.get_parameter_framebuffer(glow::DRAW_FRAMEBUFFER_BINDING);
            let new_frame = (self.mpv.api.render_update)(self.ctx) & RENDER_UPDATE_FRAME != 0;

            if self.software {
                if new_frame || !self.has_frame {
                    let stride = (rw as usize * 4).div_ceil(64) * 64;
                    let blocks = (stride * rh as usize).div_ceil(64);
                    if self.buffer.len() != blocks || self.stride != stride {
                        self.buffer = vec![Align64([0; 64]); blocks];
                        self.stride = stride;
                    }
                    let mut size: [c_int; 2] = [rw, rh];
                    let format = c"rgb0";
                    let mut stride_val: usize = stride;
                    let mut params = [
                        RenderParam { kind: RENDER_PARAM_SW_SIZE, data: size.as_mut_ptr() as *mut c_void },
                        RenderParam { kind: RENDER_PARAM_SW_FORMAT, data: format.as_ptr() as *mut c_void },
                        RenderParam { kind: RENDER_PARAM_SW_STRIDE, data: &mut stride_val as *mut _ as *mut c_void },
                        RenderParam { kind: RENDER_PARAM_SW_POINTER, data: self.buffer.as_mut_ptr() as *mut c_void },
                        RenderParam { kind: RENDER_PARAM_INVALID, data: std::ptr::null_mut() },
                    ];
                    if (self.mpv.api.render_render)(self.ctx, params.as_mut_ptr()) >= 0 {
                        let bytes = std::slice::from_raw_parts(self.buffer.as_ptr() as *const u8, stride * rh as usize);
                        gl.bind_texture(glow::TEXTURE_2D, Some(tex));
                        gl.pixel_store_i32(glow::UNPACK_ROW_LENGTH, (stride / 4) as i32);
                        gl.pixel_store_i32(glow::UNPACK_ALIGNMENT, 4);
                        gl.tex_sub_image_2d(glow::TEXTURE_2D, 0, 0, 0, rw, rh, glow::RGBA, glow::UNSIGNED_BYTE, glow::PixelUnpackData::Slice(Some(bytes)));
                        gl.pixel_store_i32(glow::UNPACK_ROW_LENGTH, 0);
                        gl.bind_texture(glow::TEXTURE_2D, None);
                        self.has_frame = true;
                    }
                }
            } else {
                let mut target = OpenGlFbo { fbo: fbo.0.get() as c_int, w: rw, h: rh, internal_format: 0 };
                let mut flip: c_int = 1;
                let mut params = [
                    RenderParam { kind: RENDER_PARAM_OPENGL_FBO, data: &mut target as *mut _ as *mut c_void },
                    RenderParam { kind: RENDER_PARAM_FLIP_Y, data: &mut flip as *mut _ as *mut c_void },
                    RenderParam { kind: RENDER_PARAM_INVALID, data: std::ptr::null_mut() },
                ];
                (self.mpv.api.render_render)(self.ctx, params.as_mut_ptr());
                self.has_frame = true;
            }

            gl.bind_framebuffer(glow::READ_FRAMEBUFFER, Some(fbo));
            gl.bind_framebuffer(glow::DRAW_FRAMEBUFFER, previous_fbo);
            gl.disable(glow::SCISSOR_TEST);
            if self.software {
                // Image logicielle : lignes de haut en bas, donc copie retournée verticalement.
                gl.blit_framebuffer(0, 0, rw, rh, x, y + h, x + w, y, glow::COLOR_BUFFER_BIT, glow::LINEAR);
            } else {
                gl.blit_framebuffer(0, 0, rw, rh, x, y, x + w, y + h, glow::COLOR_BUFFER_BIT, glow::NEAREST);
            }
            gl.bind_framebuffer(glow::READ_FRAMEBUFFER, previous_fbo);
            gl.bind_framebuffer(glow::DRAW_FRAMEBUFFER, previous_fbo);
            // La copie transmet aussi le canal alpha de l'image de mpv (0 en rendu logiciel
            // « rgb0 ») : Windows afficherait alors la zone vidéo comme transparente, donc noire.
            // On force l'opacité sur toute la zone, sans toucher aux couleurs.
            gl.enable(glow::SCISSOR_TEST);
            gl.scissor(x, y, w, h);
            gl.color_mask(false, false, false, true);
            gl.clear_color(0.0, 0.0, 0.0, 1.0);
            gl.clear(glow::COLOR_BUFFER_BIT);
            gl.color_mask(true, true, true, true);
            gl.disable(glow::SCISSOR_TEST);
            (self.mpv.api.render_report_swap)(self.ctx);
        }
    }

    /// À appeler avec le contexte OpenGL actif, avant la destruction de mpv.
    pub fn destroy(&mut self, gl: Option<&glow::Context>) {
        // SAFETY: contexte actif (on_exit d'eframe) ; libération dans l'ordre exigé par mpv.
        unsafe {
            if !self.ctx.is_null() {
                (self.mpv.api.render_free)(self.ctx);
                self.ctx = std::ptr::null_mut();
            }
            if let Some(gl) = gl {
                if let Some(t) = self.texture.take() {
                    gl.delete_texture(t);
                }
                if let Some(f) = self.fbo.take() {
                    gl.delete_framebuffer(f);
                }
            }
        }
        self._proc = None;
    }
}
