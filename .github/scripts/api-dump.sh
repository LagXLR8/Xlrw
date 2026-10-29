#!/bin/bash
# Dumps public signatures of Minecraft / Fabric API classes (Mojang names) for porting work.
# usage: api-dump.sh <mc version> <out file> <class...>
MC=$1; OUT=$2; shift 2
JAR=$(find "$HOME/.gradle" "$GITHUB_WORKSPACE" -path "*minecraftMaven*" -name "minecraft-*${MC}-*.jar" ! -name "*sources*" 2>/dev/null | tr '\n' ':')
FIRST=${JAR%%:*}
FAPI=$(find "$HOME/.gradle" "$GITHUB_WORKSPACE" -path "*remapped*" -name "*fabric-rendering-v1*${MC}*.jar" ! -name "*sources*" 2>/dev/null | head -1)
[ -z "$FAPI" ] && FAPI=$(find "$HOME/.gradle" -name "fabric-rendering-v1*.jar" ! -name "*sources*" 2>/dev/null | grep -v "1\.21\.1[^1]" | tail -1)
ALL=$(find "$HOME/.gradle" "$GITHUB_WORKSPACE" -path "*remapped*" -path "*1_21_11*" -name "*fabric-*.jar" ! -name "*sources*" 2>/dev/null | tr '\n' ':')
{
  echo "minecraft jar: $JAR"; echo "fabric rendering jar: $FAPI"; echo
  echo "=== class list (filtered) ==="
  for j in ${JAR//:/ }; do unzip -l "$j"; done | awk '{print $4}' | grep -E "client/renderer/rendertype/|client/renderer/(ShapeRenderer|RenderType|Sheets|LevelRenderer|RenderPipelines)|client/gui/render/|client/input/|client/renderer/block/model/|client/renderer/block/BlockRender|resources/model/(BlockStateModel|BakedModel|ModelManager)|net/minecraft/(util/)?Util\.class|client/renderer/state/" | grep -v '\$[0-9]' | head -300
  echo; echo "=== fabric rendering classes ==="
  echo "remapped fabric: $ALL" | tr ':' '\n' | head -5; unzip -l "$FAPI" | awk '{print $4}' | grep "\.class$" | grep -v '\$[0-9]\|impl/' | head -200
  for c in "$@"; do
    echo; echo "=== $c ==="
    javap -p -cp "$JAR$ALL$FAPI" "$c" 2>&1 | head -400
  done
  for c in net.minecraft.client.gui.render.pip.PictureInPictureRenderer net.minecraft.client.gui.render.pip.GuiSkinRenderer; do
    echo; echo "=== CODE $c ==="
    javap -c -p -cp "$JAR$ALL$FAPI" "$c" 2>&1 | head -400
  done
} > "$OUT"
