#!/usr/bin/env bash
# Run the desktop app in a throwaway sandbox: its own user.home, XDG dirs and Xvfb display,
# so it can never open (or write to) the developer's real library.
#
#   sandbox.sh start <scratch-dir> [library-dir]   build the uber-jar, launch, wait for the window
#   sandbox.sh shot  <scratch-dir> <out.png>       capture the whole display
#   sandbox.sh stop  <scratch-dir>                 close the app, then Xvfb (in that order)
#
# library-dir (e.g. design/demo-library/library) is copied to <scratch>/home/Documents/NoPapersNeeded,
# the folder onboarding proposes, so "Use this folder" picks it without a file dialog.
# Env: NPN_DISPLAY (optional unused display), NPN_SIZE (default 2560x1600),
#      NPN_JAVA_OPTS (extra -D options for the app JVM, word-split on purpose).
#      For 2x UI rendering in the same NPN_SIZE window (HiDPI-with-200%-scaling look for
#      screenshots): NPN_JAVA_OPTS='-Dsun.java2d.uiScale=2.0' — the factor is literal on this
#      path (4.0 = 4x). It is ignored if the JVM also gets
#      -Dcompose.application.configure.swing.globals=true (its Skiko autodpi overwrites it);
#      a packaged launch, which passes that flag, needs an Xft.dpi of 192 instead.
set -euo pipefail

cmd=${1:?start|shot|stop}
scratch=$(realpath -m "${2:?scratch dir}")
display_file="$scratch/display"
size=${NPN_SIZE:-2560x1600}
repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
sandbox_home="$scratch/home"

window_id() { DISPLAY=$display xdotool search --onlyvisible --name "No papers needed" 2>/dev/null | head -1; }

case "$cmd" in
start)
  [[ $scratch == "$HOME"* && $scratch != /tmp/* ]] && echo "warning: scratch dir is inside your real HOME" >&2
  mkdir -p "$sandbox_home/Documents" "$sandbox_home/.local/share" "$sandbox_home/.config"
  if [[ -n ${3:-} && ! -e "$sandbox_home/Documents/NoPapersNeeded" ]]; then
    cp -r "$3" "$sandbox_home/Documents/NoPapersNeeded"
  fi

  [[ ! -e $display_file ]] || { echo "scratch already has a display" >&2; exit 1; }
  display_arg=()
  if [[ -n ${NPN_DISPLAY:-} ]]; then display_arg=("$NPN_DISPLAY"); fi
  Xvfb "${display_arg[@]}" -displayfd 3 -screen 0 "${size}x24" -nolisten tcp \
    3>"$scratch/display.ready" >"$scratch/xvfb.log" 2>&1 &
  xvfb_pid=$!
  for _ in $(seq 100); do
    [[ -s "$scratch/display.ready" ]] && break
    kill -0 "$xvfb_pid" 2>/dev/null || { cat "$scratch/xvfb.log" >&2; exit 1; }
    sleep 0.1
  done
  [[ -s "$scratch/display.ready" ]] || { kill "$xvfb_pid"; exit 1; }
  display=":$(<"$scratch/display.ready")"
  echo "$display" >"$display_file"
  echo "$xvfb_pid" >"$scratch/xvfb.pid"
  trap '"$0" stop "$scratch"' ERR

  (cd "$repo" && ./gradlew :desktopApp:packageUberJarForCurrentOS --console=plain -q)
  version=$(sed -n 's/^npn\.version=//p' "$repo/gradle.properties")
  jar="$repo/desktopApp/build/compose/jars/no-papers-needed-linux-x64-$version.jar"

  # JDK 25 takes user.home from passwd, not $HOME: -Duser.home is what isolates the app.
  # XDG_* would override user.home in PlatformDirs, so pin them too. cwd = scratch so a
  # crash's hs_err_pid*.log lands there, not in the repo.
  read -r -a extra_java_opts <<<"${NPN_JAVA_OPTS:-}"
  (
    cd "$scratch"
    env HOME="$sandbox_home" XDG_DATA_HOME="$sandbox_home/.local/share" XDG_CONFIG_HOME="$sandbox_home/.config" \
      LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 DISPLAY="$display" \
      java -Duser.home="$sandbox_home" -Duser.language=en -Duser.country=US "${extra_java_opts[@]}" -jar "$jar" \
      >"$scratch/app.log" 2>&1 &
    echo $! >"$scratch/app.pid"
  )

  for _ in $(seq 60); do
    w=$(window_id) && [[ -n $w ]] && break
    sleep 1
  done
  [[ -n ${w:-} ]] || { echo "app window never appeared; see $scratch/app.log" >&2; "$0" stop "$scratch"; exit 1; }
  DISPLAY=$display xdotool windowmove "$w" 0 0 windowsize "$w" "${size%x*}" "${size#*x}"
  sleep 2
  # Proof of isolation: the app's state dir must be in the sandbox.
  [[ -d "$sandbox_home/.local/share/no-papers-needed" ]] || {
    echo "app state is NOT in the sandbox; stopping it" >&2; "$0" stop "$scratch"; exit 1; }
  echo "window $w on $display, home $sandbox_home"
  ;;
shot)
  display=$(<"$display_file")
  kill -0 "$(<"$scratch/xvfb.pid")"
  DISPLAY=$display import -window root "${3:?out.png}"
  ;;
stop)
  if [[ -f "$scratch/app.pid" ]]; then
    pid=$(<"$scratch/app.pid")
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 15); do
      [[ -d /proc/$pid && $(ps -o stat= -p "$pid") != Z* ]] || break
      sleep 1
    done
    # Still alive after the grace period: force it, or killing Xvfb below crashes it in libX11.
    if [[ -d /proc/$pid && $(ps -o stat= -p "$pid") != Z* ]]; then
      kill -9 "$pid" 2>/dev/null || true
      sleep 1
    fi
    rm -f "$scratch/app.pid"
  fi
  # Killing Xvfb while the JVM is alive crashes it in libX11; only now is it safe.
  if [[ -f "$scratch/xvfb.pid" ]]; then
    kill "$(<"$scratch/xvfb.pid")" 2>/dev/null || true
    rm -f "$scratch/xvfb.pid" "$display_file" "$scratch/display.ready"
  fi
  ;;
*) echo "unknown command: $cmd" >&2; exit 2 ;;
esac
