import { readdir } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const directories = ['src/api', 'src/interview', 'src/interview/voice', 'src/utils']
const files = []
for (const directory of directories) {
  const entries = await readdir(join(root, directory), { withFileTypes: true })
    .catch(error => { if (error.code === 'ENOENT') return []; throw error })
  for (const entry of entries) {
    if (entry.isFile() && /\.test\.(js|ts)$/.test(entry.name)) {
      files.push(join(root, directory, entry.name))
    }
  }
}
if (!files.length) throw new Error('未发现测试文件')
const child = spawn(process.execPath, ['--import', 'tsx', '--test', ...files.sort()], {
  cwd: root, stdio: 'inherit',
})
child.once('error', error => { console.error(error); process.exitCode = 1 })
child.once('exit', code => { process.exitCode = code ?? 1 })
