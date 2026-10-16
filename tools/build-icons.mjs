#!/usr/bin/env node
/**
 * 生成 Compose 用的图标（app/src/main/java/ink/kuke/chat/ui/icons/KcIcons.kt）。
 *
 * 来源和设计稿第 03 节一致：
 *   - 电脑版 KukeChat-Client/src/components/ui/Icon.tsx 的全部图标，键名不变（message = MessageCircle …）
 *   - 手机补充的 lucide 图标（设计稿 KC_ICONS.extra），键名是 lucide 名的驼峰（arrow-left → arrowLeft）
 * 线稿数据直接取 KukeChat-Client/node_modules/lucide-react 里的 __iconNode，保证两端是同一套。
 *
 *   node tools/build-icons.mjs ../KukeChat-Client
 */
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const client = process.argv[2] ?? join(root, '..', 'KukeChat-Client');
const lucideDir = join(client, 'node_modules', 'lucide-react', 'dist', 'esm');

// 设计稿「手机补充（82 · lucide 名）」
const EXTRA = ['arrow-left', 'arrow-up', 'arrow-down', 'chevron-down', 'chevron-up', 'ellipsis-vertical', 'keyboard', 'circle-plus', 'audio-lines', 'reply', 'forward', 'square-pen', 'qr-code', 'scan-line', 'flame', 'trophy', 'crown', 'gift', 'party-popper', 'zap', 'compass', 'layout-grid', 'wifi', 'battery-full', 'signal-high', 'hash', 'map-pin', 'gamepad-2', 'wand-sparkles', 'rocket', 'medal', 'gavel', 'book-open', 'history', 'calendar', 'circle-check', 'kanban', 'list-checks', 'timer', 'trending-up', 'globe', 'smartphone', 'tablet', 'type', 'bell-ring', 'key-round', 'fingerprint', 'sticker', 'music', 'pen-tool', 'gem', 'heart-handshake', 'pause', 'image-plus', 'paperclip', 'circle-help', 'messages-square', 'sparkle', 'swatch-book', 'layers', 'hand', 'award', 'badge-check', 'circle-dot', 'circle', 'panel-left', 'panel-right', 'grip-vertical', 'arrow-up-right', 'x-circle', 'lightbulb', 'target', 'wallet', 'receipt', 'sun-moon', 'user-round-check', 'mail', 'route', 'brush', 'cake', 'leaf', 'droplet', 'shapes'];

// 电脑版自己画的（不在 lucide 里）：照 Icon.tsx 的 RedPacket 抄
const CUSTOM = {
  redPacket: [
    ['rect', { x: 5, y: 2, width: 14, height: 20, rx: 2.5 }],
    ['path', { d: 'M5 7.6C6.9 9.6 8.4 10.6 9.9 11' }],
    ['path', { d: 'M19 7.6C17.1 9.6 15.6 10.6 14.1 11' }],
    ['circle', { cx: 12, cy: 11.6, r: 2.2 }],
  ],
};

/** lucide-react 的导出名（含别名）→ 图标文件 */
const exportToFile = new Map();
for (const line of readFileSync(join(lucideDir, 'lucide-react.mjs'), 'utf8').split('\n')) {
  const match = line.match(/^export \{ (.+) \} from '\.\/icons\/([^']+)\.mjs';/);
  if (!match) continue;
  for (const part of match[1].split(',')) {
    const name = part.trim().replace(/^default as /, '');
    exportToFile.set(name, match[2]);
  }
}

function iconNode(file) {
  const source = readFileSync(join(lucideDir, 'icons', `${file}.mjs`), 'utf8');
  const body = source.slice(source.indexOf('const __iconNode = ') + 'const __iconNode = '.length, source.indexOf(';\nconst '));
  // __iconNode 是 JS 字面量（键没有引号），用 Function 求值；来源是本地 node_modules 里的 lucide
  return Function(`"use strict"; return (${body});`)();
}

const n = (value) => Number(value);
function toPath([tag, attrs]) {
  switch (tag) {
    case 'path':
      return attrs.d;
    case 'circle': {
      const [cx, cy, r] = [n(attrs.cx), n(attrs.cy), n(attrs.r)];
      return `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;
    }
    case 'ellipse': {
      const [cx, cy, rx, ry] = [n(attrs.cx), n(attrs.cy), n(attrs.rx), n(attrs.ry)];
      return `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2 * rx} 0a${rx} ${ry} 0 1 0 ${-2 * rx} 0`;
    }
    case 'rect': {
      const [x, y, w, h] = [n(attrs.x ?? 0), n(attrs.y ?? 0), n(attrs.width), n(attrs.height)];
      const rx = Math.min(n(attrs.rx ?? attrs.ry ?? 0), w / 2);
      const ry = Math.min(n(attrs.ry ?? attrs.rx ?? 0), h / 2);
      if (!rx && !ry) return `M${x} ${y}h${w}v${h}h${-w}Z`;
      return `M${x + rx} ${y}h${w - 2 * rx}a${rx} ${ry} 0 0 1 ${rx} ${ry}v${h - 2 * ry}a${rx} ${ry} 0 0 1 ${-rx} ${ry}h${-(w - 2 * rx)}a${rx} ${ry} 0 0 1 ${-rx} ${-ry}v${-(h - 2 * ry)}a${rx} ${ry} 0 0 1 ${rx} ${-ry}Z`;
    }
    case 'line':
      return `M${attrs.x1} ${attrs.y1}L${attrs.x2} ${attrs.y2}`;
    case 'polyline':
    case 'polygon': {
      const pts = String(attrs.points).trim().split(/[\s,]+/).map(Number);
      let d = `M${pts[0]} ${pts[1]}`;
      for (let i = 2; i < pts.length; i += 2) d += `L${pts[i]} ${pts[i + 1]}`;
      return tag === 'polygon' ? `${d}Z` : d;
    }
    default:
      throw new Error(`不认识的 SVG 元素 ${tag}`);
  }
}

const camel = (kebab) => kebab.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase());

// 电脑版键名 → lucide 导出名（Icon.tsx 的 icons 对象）
const iconSource = readFileSync(join(client, 'src', 'components', 'ui', 'Icon.tsx'), 'utf8');
const mapBlock = iconSource.slice(iconSource.indexOf('const icons = {'), iconSource.indexOf('} satisfies'));
const desktop = [...mapBlock.matchAll(/^\s+(\w+): (\w+),?$/gm)].map((m) => [m[1], m[2]]);

const entries = new Map();
for (const [key, component] of desktop) {
  if (CUSTOM[key]) {
    entries.set(key, { from: 'Icon.tsx', paths: CUSTOM[key].map(toPath) });
    continue;
  }
  const file = exportToFile.get(component);
  if (!file) throw new Error(`lucide-react 里找不到 ${component}（键 ${key}）`);
  entries.set(key, { from: file, paths: iconNode(file).map(toPath) });
}
for (const name of EXTRA) {
  const file = exportToFile.has(name) ? name : null;
  const resolved = file ?? [...exportToFile.entries()].find(([exported]) => exported.toLowerCase() === camel(name).toLowerCase())?.[1];
  const path = resolved ?? name;
  let node;
  try {
    node = iconNode(path);
  } catch {
    throw new Error(`lucide-react 里找不到 ${name}`);
  }
  const key = camel(name);
  if (!entries.has(key)) entries.set(key, { from: path, paths: node.map(toPath) });
}

const lines = [...entries.entries()].map(([key, { from, paths }]) =>
  `    /** ${from} */\n    val ${key}: ImageVector by lazy { lucide("${key}", ${paths.map((d) => JSON.stringify(d)).join(', ')}) }`,
);
const kotlin = `// 由 tools/build-icons.mjs 生成，不要手改。来源：lucide-react ${JSON.parse(readFileSync(join(client, 'node_modules', 'lucide-react', 'package.json'), 'utf8')).version}（同电脑版 Icon.tsx）
package ink.kuke.chat.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

@Suppress("unused")
object KcIcons {
${lines.join('\n')}
}
`;
const out = join(root, 'app', 'src', 'main', 'java', 'ink', 'kuke', 'chat', 'ui', 'icons', 'KcIcons.kt');
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, kotlin);
console.log(`${entries.size} 个图标 → ${out}`);
