# Terminal Ligera

Terminal propia y ligera para Android. En 1.0 solo corría los
comandos del shell de Android (`/system/bin/sh`). En 2.0 trae
un instalador experimental que baja el bootstrap oficial de
Termux y así ya se le puede instalar `git` y probar Muse Code.

## Qué hace

- Escribes un comando y ves su salida, con el prompt `$`.
- `cd` persistente entre comandos: `cd`, `cd ..`, `cd /sdcard`, `cd ~/`.
- Comandos propios: `help`, `clear`, `pwd`, `history`, `estado`,
  `acerca`, `salir`.
- Historial con las flechas ↑ ↓, sin repetir el mismo comando seguido.
- Atajos: `ls`, `pwd`, `date`, `help`.
- Teclas extra para el teléfono: `/ - | ~ $`.
- La salida se recorta si es muy larga y cada comando tiene
  un límite de 30 segundos, para que no se cuelgue la app.
- Al girar el teléfono no se pierde lo escrito ni la carpeta actual.

## 2.4 — bash ya corre: targetSdk 28 como Termux

En la 2.3 el `instalar` ya funcionaba perfecto en el teléfono
(SHA-256 correcto, 3,480 archivos, 1,117 enlaces creados, 444
archivos con 0755), pero `bash --version` y `curl --version`
seguían con Permission denied. Causa verificada: Android 10+
(API 29) bloquea que una app ejecute binarios de su propia
carpeta de datos (W^X, política SELinux) cuando targetSdk >= 29,
y la app tenía targetSdk 35. Termux lo resuelve con targetSdk 28.

Cambios en la 2.4 (versionCode 6):
- `targetSdk 28` en app/build.gradle (compileSdk sigue en 35,
  minSdk 24). Es la misma técnica de Termux; no afecta a la
  instalación por sideload (el piso de Android 14 es targetSdk 23).
- El fallback por linker ya no pasa por `bash -c`: en la 2.3 el
  bash cargado por el linker volvía a intentar ejecutar `bash`
  y moría con el mismo Permission denied. Ahora los comandos
  simples (bash, curl, git, pkg...) se corren con el binario
  directo por el linker (`linker64 <binario> <args>`).
- `reparar` ahora prueba bash directo ADEMÁS de por el linker,
  para confirmar en el teléfono que el fix funcionó.

Para probar tras instalar: `reparar` (debe decir "prueba bash
(directo): SÍ, bash corre"), luego `bash --version`,
`curl --version`, `pkg-instalar git`, `git --version`,
`muse-instalar`.

## 2.3 — firma estable: se instala y actualiza normal

Cada build de Actions salía con un certificado distinto (cada
runner genera su propio debug keystore) y Android no dejaba
actualizar sin desinstalar. Al desinstalar se borraba el
bootstrap y había que bajarlo otra vez. Verificado: los APK
1.0, 2.0 y 2.1 traían certificados SHA-256 distintos.

Cambios en la 2.3 (versionCode 5):
- Una sola llave en el repo: `app/terminal-ligera.keystore`
  (PKCS12, alias `androiddebugkey`, contraseña `android`,
  certificado SHA-256
  FF:45:B3:64:94:13:82:E5:5F:7E:DA:2C:01:85:B8:D5:3C:28:24:17:3F:55:DD:E1:09:D8:08:AD:34:AB:51:59,
  válido hasta 2054). Es una llave de desarrollo, no de Play
  Store: quien la tenga puede firmar actualizaciones de esta
  app, por eso solo se usa para Terminal Ligera.
- El workflow ahora compila en release (`assembleRelease`)
  con esa llave y sube el artifact `app-release`.
- Resultado: el APK se instala con el instalador normal de
  Android y las nuevas versiones actualizan sin desinstalar,
  conservando el bootstrap ya instalado.

OJO al actualizar desde una versión vieja: la 2.3 trae firma
nueva, distinta a la de la 1.0/2.0/2.1, así que la PRIMERA
vez sí hay que desinstalar la vieja e instalar la 2.3. Desde
la 2.3 en adelante, ya actualiza sola.

## 2.2 — enlaces en su carpeta + prueba bash por linker

El `reparar` de la 2.1 puso los permisos (bash ya decía
ejecutable: sí) pero los enlaces siguieron en 0 y bash tampoco
arrancó por el linker: `CANNOT LINK EXECUTABLE .../bin/bash:
library "libreadline.so.8" not found`. Dos lecciones:

1. **Los enlaces:** el archivo real vive en la MISMA carpeta del
   enlace (el real `libreadline.so.8.3` está en `lib/` para el
   enlace `lib/libreadline.so.8`). Mi fix de la 2.1 lo buscaba
   en la raíz de `usr/` y por eso resolvía 0 de 1213 líneas
   (verificado contra el zip oficial; con la carpeta correcta
   resuelven 1106 directas y el resto por cadenas, con varias
   pasadas).
2. **Ejecutar directo no se puede:** aunque bash tenga 0755,
   Android bloquea ejecutar binarios creados por la app en su
   carpeta (error 13). La vía real es el linker del sistema.

Cambios en la 2.2 (versionCode 4):
- `crearEnlaces` busca el real en la carpeta del enlace (con
  respaldo en `usr/`) y hace hasta 3 pasadas para las cadenas.
- `reparar` ahora reporta si existe `lib/libreadline.so.8` y
  prueba bash por el linker (`linker64 bash -c "echo ..."`)
  diciendo si corre.
- El aviso del fallback ya no manda a `reparar` cuando el
  linker sí funciona.

Para probar (bootstrap ya instalado, no se re-baja nada):
`reparar` y luego `bash --version`.

## 2.1 — arregla el error 13 (Permission denied)

En el teléfono, `bash --version` falló con `error=13, Permission denied`
aunque el bootstrap sí se había instalado bien (SHA correcto, 3,480
archivos, bash/curl/apt/pkg presentes). Dos causas:

1. **Permisos:** la 2.0 ponía el permiso de ejecución con un
   `chmod -R +x` por el shell, sin verificar si funcionó. En la 2.1
   se pone con `Os.chmod` directo (0755) a todo `bin/` y `libexec/`
   y se verifica que `bin/bash` quede ejecutable.
2. **Enlaces:** `SYMLINKS.txt` tiene el formato
   `ARCHIVO_REAL ← ./ruta/del/enlace` (la izquierda es el archivo
   que existe, la derecha el enlace a crear en esa carpeta). La 2.0
   lo leía al revés y en la raíz, por eso salieron 0 enlaces.

Cambios en la 2.1 (versionCode 3):
- Comando nuevo `reparar`: vuelve a poner permisos y enlaces sin
  bajar el bootstrap otra vez. Si `instalar` se corre con el
  bootstrap ya instalado, también repara en vez de salir.
- Si bash no arranca, los comandos caen al linker del sistema
  (`/system/bin/linker64`, que lee el binario en vez de ejecutarlo)
  y luego al `sh` de Android, avisando cuál se usó, en vez de
  quedarse atorados.
- `estado` ahora muestra si `bin/bash` es ejecutable.

Para probar en el teléfono (con el bootstrap de la 2.0 ya instalado,
no hace falta reinstalar): `reparar`, luego `bash --version`,
`curl --version`, `pkg-instalar git`, `git --version`.

## 2.0 — instalar lo que falta (experimental)

En la app, en orden:

```
estado
instalar
bash --version
curl --version
pkg-instalar git
git --version
muse-instalar
muse --version
```

- `instalar` baja el bootstrap oficial de Termux
  `bootstrap-2026.11.01-r1+apt.android-7` para aarch64
  (~32 MB, SHA-256 verificado
  `5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae`)
  de `github.com/termux/termux-packages` y lo descomprime en
  la carpeta privada de la app (`filesDir/usr`, ~150 MB).
  Trae `bash`, `curl`, `apt`, `pkg` y `dpkg`. Git no viene:
  se instala después con `pkg-instalar git`.
- Después de instalar, los comandos corren con ese bash,
  con `PREFIX`, `HOME`, `PATH` y `LD_LIBRARY_PATH` apuntando
  a `usr/`. Sin root, sin tocar `/system` ni la carpeta de Termux.
- `muse-instalar` corre el instalador oficial de Muse Code
  (`curl -fsSL https://dev.meta.ai/install.sh | bash`, ~100 MB).

### Límites honestos de la 2.0

- Es un intento, no una garantía. El bootstrap está hecho para
  Termux (`com.termux`); aquí vive en `com.nexos.terminal`, así
  que algunos scripts con rutas de Termux escritas pueden fallar.
  El error exacto saldrá en pantalla.
- Muse Code es interactivo: necesita una terminal con PTY y lee
  su login desde `/dev/tty`. Esta app corre cada comando de una
  sola vez, sin PTY, con límite de tiempo. Por eso `muse --version`
  sí puede funcionar, pero `muse login` y `muse` a secas muy
  probablemente fallen o se corten. Si tienes `META_API_KEY`,
  prueba `muse exec` en vez de `muse`.
- `nano`, `vi` y `ssh` interactivo tampoco corren, por lo mismo.
- Sin bootstrap, la 2.0 se comporta igual que la 1.0.

## Comandos que sí corren (sin bootstrap)

`pwd`, `ls`, `ls -la`, `echo`, `date`, `whoami`, `id`, `uname -a`,
`cat <archivo>`, `env`, `getprop`, `mkdir`, `touch`, `cp`, `mv`, `rm`.

Ojo con `rm`: borra de verdad. El shell corre como usuario de
la app, sin root, así que no toca el sistema, pero sí puede borrar
archivos de la app y de carpetas a las que tenga acceso.

## Datos técnicos

- Paquete: `com.nexos.terminal`
- Versión: 2.0 (versionCode 2)
- compileSdk / targetSdk 35, minSdk 24
- Java 17, Gradle 8.9, AGP 8.7.3, Kotlin 1.9.24
- Sin dependencias externas. Internet solo para bajar el bootstrap
  y los paquetes que el usuario pida. Sin cuentas.

## Cómo compilar

Cada push a `main` o `master` dispara el workflow de GitHub Actions
**"Build APK (debug)"** (`.github/workflows/build-apk.yml`), que genera
el APK debug y lo sube como artifact `app-debug`.

También se puede compilar en local con:

```bash
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`.

## Cómo subirlo (desde Termux)

El repo `terminal-ligera` ya existe (creado para la 1.0).
Desde `~/code/terminal-ligera`, con los cambios de la 2.0:

```bash
cd ~/code/terminal-ligera
git add -A
git commit -m "Terminal Ligera 2.4: targetSdk 28 + linker directo"
git push origin main
```
