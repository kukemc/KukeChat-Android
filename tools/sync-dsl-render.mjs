#!/usr/bin/env node
/**
 * 把 KukeChat-Client 的积木渲染页（dist-dsl-render/）同步进 app/src/main/assets/dsl-render/。
 *
 * 聊天里的积木消息（```gandi-dsl 围栏）在一个隐藏 WebView 里画成图（见 core/dsl/DslRenderer.kt），
 * 画图用的页面、编译器和 gandi-blocks 积木库都来自电脑版仓库，契约见 KukeChat-Client/docs/gandi-dsl-blocks.md。
 * 拷进来的文件入库：正式包不依赖旁边有没有电脑版仓库。
 *
 *   node tools/sync-dsl-render.mjs                     # 先在 ../KukeChat-Client 跑 npm run build:dsl-render，再拷
 *   node tools/sync-dsl-render.mjs <client 目录>       # 电脑版仓库在别处
 *   node tools/sync-dsl-render.mjs --no-build          # 不重新构建，直接拷现成的 dist-dsl-render/
 *
 * 电脑版仓库的位置也可以用环境变量 KUKECHAT_CLIENT 指定。
 */
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const args = process.argv.slice(2);
const noBuild = args.includes('--no-build');
const clientArg = args.find((arg) => !arg.startsWith('--'));
const client = resolve(clientArg ?? process.env.KUKECHAT_CLIENT ?? join(root, '..', 'KukeChat-Client'));
const source = join(client, 'dist-dsl-render');
const target = join(root, 'app', 'src', 'main', 'assets', 'dsl-render');

if (!existsSync(join(client, 'package.json'))) {
  console.error(`找不到电脑版仓库：${client}（用参数或 KUKECHAT_CLIENT 指定）`);
  process.exit(1);
}
if (!noBuild) {
  console.log(`构建积木渲染页：${client}`);
  execSync('npm run build:dsl-render', { cwd: client, stdio: 'inherit' });
}
for (const file of ['index.html', 'kuke-dsl-render.js', 'gandi-blocks/blockly_compressed_vertical.js']) {
  if (!existsSync(join(source, file))) {
    console.error(`渲染页不完整，缺 ${file}：先在电脑版仓库跑 npm run build:dsl-render`);
    process.exit(1);
  }
}

// 不用 cpSync(recursive)：Node 24 在 Windows 的中文路径下会直接崩溃（0xC0000409）
function copyDir(from, to) {
  mkdirSync(to, { recursive: true });
  for (const name of readdirSync(from)) {
    const src = join(from, name);
    const dst = join(to, name);
    if (statSync(src).isDirectory()) copyDir(src, dst);
    else copyFileSync(src, dst);
  }
}

rmSync(target, { recursive: true, force: true });
copyDir(source, target);

function sizeOf(dir) {
  return readdirSync(dir).reduce((sum, name) => {
    const path = join(dir, name);
    const stat = statSync(path);
    return sum + (stat.isDirectory() ? sizeOf(path) : stat.size);
  }, 0);
}
console.log(`已同步到 ${target}（${(sizeOf(target) / 1024 / 1024).toFixed(2)} MB）`);
