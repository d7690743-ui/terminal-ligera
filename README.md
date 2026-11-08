# Terminal Ligera

Terminal propia y ligera para Android, sin Termux y sin paquetes.
Corre comandos del shell de Android (`/system/bin/sh`) y muestra
la salida en pantalla.

## Qué hace

- Escribes un comando y ves su salida, con el prompt `$`.
- `cd` persistente entre comandos: `cd`, `cd ..`, `cd /sdcard`, `cd ~/`.
- Comandos propios: `help`, `clear`, `pwd`, `history`, `acerca`, `salir`.
- Historial con las flechas ↑ ↓, sin repetir el mismo comando seguido.
- Atajos: `ls`, `pwd`, `date`, `help`.
- Teclas extra para el teléfono: `/ - | ~ $`.
- La salida se recorta si es muy larga y cada comando tiene
  un límite de 10 segundos, para que no se cuelgue la app.
- Al girar el teléfono no se pierde lo escrito ni la carpeta actual.

## Comandos que sí corren

`pwd`, `ls`, `ls -la`, `echo`, `date`, `whoami`, `id`, `uname -a`,
`cat <archivo>`, `env`, `getprop`, `mkdir`, `touch`, `cp`, `mv`, `rm`.

Ojo con `rm`: borra de verdad. El shell corre como usuario de
la app, sin root, así que no toca el sistema, pero sí puede borrar
archivos de la app y de carpetas a las que tenga acceso.

## Lo que NO corre

`pkg`, `apt`, `python`, `pip`, `git`, `nano`, `vi`, `ssh` ni nada
interactivo. Para eso sigue Termux. Esta terminal es solo el shell
que ya trae Android, no un Linux completo.

## Datos técnicos

- Paquete: `com.nexos.terminal`
- Versión: 1.0 (versionCode 1)
- compileSdk / targetSdk 35, minSdk 24
- Java 17, Gradle 8.9, AGP 8.7.3, Kotlin 1.9.24
- Sin dependencias externas, sin internet, sin cuentas

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

```bash
cd ~/code
# descomprime el zip aquí, debe quedar ~/code/terminal-ligera
cd terminal-ligera
git init -b main
git add -A
git commit -m "Terminal Ligera 1.0"
gh repo create terminal-ligera --public --source=. --remote=origin --push
# si el repo ya existe, usa en su lugar:
# git remote add origin https://github.com/d7690743-ui/terminal-ligera.git
# git push -u origin main
```

Si `gh` no puede crear el repo, crea el repo vacío `terminal-ligera`
en GitHub y luego haz el push a mano.
