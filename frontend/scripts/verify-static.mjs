import { readFile, stat } from 'node:fs/promises'
import { resolve } from 'node:path'

const output = resolve('../backend/src/main/resources/static')
const indexPath = resolve(output, 'index.html')
const html = await readFile(indexPath, 'utf8')
const references = [...html.matchAll(/(?:src|href)="\/(assets\/[^"?#]+)"/g)].map((match) => match[1])

if (!references.length) throw new Error('Static bundle verification failed: index.html has no hashed asset references')
for (const asset of references) {
  await stat(resolve(output, asset))
}
console.log(`Static bundle verified: ${references.length} referenced assets exist`)
