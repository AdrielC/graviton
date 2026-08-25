import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import DOMPurify from 'dompurify'

// Mermaid's parser sanitizes labels even though this check never renders DOM.
// Its Node import exposes the DOMPurify factory, so provide the two parse-time
// methods before loading Mermaid. Browser rendering still uses real DOMPurify.
DOMPurify.sanitize ??= value => value
DOMPurify.addHook ??= () => {}

const { default: mermaid } = await import('mermaid')

const ignoredDirectories = new Set(['.vitepress', 'node_modules', 'public'])
const fencePattern = /^[\t ]*```mermaid[^\r\n]*\r?\n([\s\S]*?)^[\t ]*```[\t ]*$/gm

async function markdownFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = []

  for (const entry of entries) {
    if (entry.isDirectory() && !ignoredDirectories.has(entry.name)) {
      files.push(...await markdownFiles(path.join(directory, entry.name)))
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      files.push(path.join(directory, entry.name))
    }
  }

  return files
}

const root = process.cwd()
const files = await markdownFiles(root)
const failures = []
let diagramCount = 0

for (const file of files) {
  const markdown = await readFile(file, 'utf8')
  let match

  while ((match = fencePattern.exec(markdown)) !== null) {
    diagramCount += 1
    const line = markdown.slice(0, match.index).split(/\r?\n/).length

    try {
      await mermaid.parse(match[1])
    } catch (error) {
      failures.push({
        file: path.relative(root, file),
        line,
        reason: error instanceof Error ? error.message : String(error),
      })
    }
  }
}

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(`${failure.file}:${failure.line}: ${failure.reason}`)
  }
  process.exitCode = 1
} else {
  console.log(`Validated ${diagramCount} Mermaid diagrams across ${files.length} Markdown files.`)
}
