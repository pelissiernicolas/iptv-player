//! Chiffrement des données sensibles (identifiants Xtream) avec DPAPI : la clé est gérée par
//! Windows et liée au compte de l'utilisateur ; le fichier copié sur un autre compte ou un
//! autre PC est illisible.

#[cfg(windows)]
mod imp {
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    };

    const ENTROPY: &[u8] = b"fr.pelis.iptv/profiles/v1";

    fn blob(data: &[u8]) -> CRYPT_INTEGER_BLOB {
        CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_ptr() as *mut u8 }
    }

    fn take(out: CRYPT_INTEGER_BLOB) -> Vec<u8> {
        // SAFETY: tampon alloué par DPAPI (LocalAlloc), de taille cbData, libéré une seule fois.
        unsafe {
            let v = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
            LocalFree(out.pbData as _);
            v
        }
    }

    pub fn protect(data: &[u8]) -> Result<Vec<u8>, String> {
        let input = blob(data);
        let entropy = blob(ENTROPY);
        let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
        // SAFETY: pointeurs valides pendant l'appel ; `out` est rempli par DPAPI.
        let ok = unsafe {
            CryptProtectData(
                &input,
                std::ptr::null(),
                &entropy,
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out,
            )
        };
        if ok == 0 { Err("chiffrement Windows (DPAPI) indisponible".into()) } else { Ok(take(out)) }
    }

    pub fn unprotect(data: &[u8]) -> Result<Vec<u8>, String> {
        let input = blob(data);
        let entropy = blob(ENTROPY);
        let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
        // SAFETY: idem `protect`.
        let ok = unsafe {
            CryptUnprotectData(
                &input,
                std::ptr::null_mut(),
                &entropy,
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out,
            )
        };
        if ok == 0 { Err("données illisibles sur ce compte Windows".into()) } else { Ok(take(out)) }
    }
}

#[cfg(not(windows))]
mod imp {
    pub fn protect(_data: &[u8]) -> Result<Vec<u8>, String> {
        Err("stockage sécurisé disponible uniquement sous Windows".into())
    }

    pub fn unprotect(_data: &[u8]) -> Result<Vec<u8>, String> {
        Err("stockage sécurisé disponible uniquement sous Windows".into())
    }
}

pub use imp::{protect, unprotect};

#[cfg(all(test, windows))]
mod tests {
    #[test]
    fn roundtrip() {
        let enc = super::protect(b"secret").unwrap();
        assert!(!enc.windows(6).any(|w| w == b"secret"));
        assert_eq!(super::unprotect(&enc).unwrap(), b"secret");
        assert!(super::unprotect(b"pas chiffre").is_err());
    }
}
