export function yamlDocument(value) {
  return `${yamlValue(value, 0)}\n`
}

function yamlValue(value, indent) {
  const space = " ".repeat(indent)
  if (Array.isArray(value)) {
    return value.map(item => {
      if (item && typeof item === "object") return `${space}-\n${yamlValue(item, indent + 2)}`
      return `${space}- ${yamlScalar(item)}`
    }).join("\n")
  }
  return Object.entries(value).map(([key, child]) => {
    if (child && typeof child === "object") return `${space}${key}:\n${yamlValue(child, indent + 2)}`
    return `${space}${key}: ${yamlScalar(child)}`
  }).join("\n")
}

function yamlScalar(value) {
  if (value === null) return "null"
  if (typeof value === "boolean" || typeof value === "number") return String(value)
  return `'${String(value).replaceAll("'", "''")}'`
}

export function parseYamlDocument(source) {
  const rawLines = source.replace(/\r/g, "").split("\n")
  const lines = []
  for (let index = 0; index < rawLines.length; index++) {
    let line = rawLines[index]
    const flowClose = /^\s*[^#][^:]*:\s*\[/.test(line) ? "]"
      : /^\s*[^#][^:]*:\s*\{/.test(line) ? "}" : null
    if (flowClose && !line.trimEnd().endsWith(flowClose)) {
      while (index + 1 < rawLines.length && !line.trimEnd().endsWith(flowClose)) {
        index += 1
        line = `${line.trimEnd()} ${rawLines[index].trim()}`
      }
    }
    if (/^\s*[^#][^:]*:\s*\[\s*$/.test(line) && rawLines[index + 1]?.trim() === "]") {
      lines.push(line.replace(/\[\s*$/, "[]"))
      index += 1
    } else {
      lines.push(line)
    }
  }

  const indentation = line => line.match(/^\s*/)[0].length
  const nextContent = start => {
    let index = start
    while (index < lines.length && lines[index].trim() === "") index += 1
    return index
  }
  const splitEntry = text => {
    const colon = text.indexOf(":")
    if (colon < 0) throw new Error(`unsupported YAML line: ${text}`)
    return [text.slice(0, colon).trim(), text.slice(colon + 1).trim()]
  }
  const scalar = text => {
    if (text.startsWith("'") && text.endsWith("'")) return text.slice(1, -1).replaceAll("''", "'")
    if (text.startsWith('"') && text.endsWith('"')) return JSON.parse(text)
    if (text === "true") return true
    if (text === "false") return false
    if (text === "null" || text === "~") return null
    if (text.startsWith("{") && text.endsWith("}")) {
      const body = text.slice(1, -1).trim()
      if (!body) return {}
      return Object.fromEntries(splitFlowItems(body).map(item => {
        const [key, value] = splitEntry(item)
        return [key, scalar(value)]
      }))
    }
    if (text.startsWith("[") && text.endsWith("]")) {
      const body = text.slice(1, -1).trim()
      if (!body) return []
      return splitFlowItems(body).map(item => scalar(item.trim()))
    }
    if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(text)) return Number(text)
    if (text === "[]") return []
    if (text === "{}") return {}
    return text
  }
  const splitFlowItems = text => {
    const items = []
    let start = 0
    let quote = null
    for (let index = 0; index < text.length; index += 1) {
      const character = text[index]
      if (quote) {
        if (character === quote && (quote !== "'" || text[index + 1] !== "'")) quote = null
        else if (quote === "'" && character === "'" && text[index + 1] === "'") index += 1
      } else if (character === "'" || character === '"') quote = character
      else if (character === ",") {
        items.push(text.slice(start, index))
        start = index + 1
      }
    }
    items.push(text.slice(start))
    return items
  }
  const scalarOrBlock = (text, lineIndex, parentIndent) => {
    if (text !== "|-" && text !== "|" && text !== ">-" && text !== ">") return [scalar(text), lineIndex]
    let cursor = lineIndex
    const values = []
    let contentIndent = null
    while (cursor < lines.length) {
      const line = lines[cursor]
      if (line.trim() === "") {
        values.push("")
        cursor += 1
        continue
      }
      const indent = indentation(line)
      if (indent <= parentIndent) break
      contentIndent ??= indent
      values.push(line.slice(Math.min(contentIndent, line.length)))
      cursor += 1
    }
    const folded = text.startsWith(">") ? values.join(" ") : values.join("\n")
    return [text.endsWith("-") ? folded : `${folded}\n`, cursor]
  }
  const parseBlock = (start, expectedIndent) => {
    let index = nextContent(start)
    if (index >= lines.length) return [null, index]
    const sequence = indentation(lines[index]) === expectedIndent && lines[index].trim().startsWith("-")
    const result = sequence ? [] : {}
    while (index < lines.length) {
      index = nextContent(index)
      if (index >= lines.length) break
      const line = lines[index]
      const indent = indentation(line)
      if (indent < expectedIndent) break
      if (indent > expectedIndent) throw new Error(`unexpected YAML indentation at line ${index + 1}`)
      const trimmed = line.trim()
      if (sequence) {
        if (!trimmed.startsWith("-")) break
        const itemText = trimmed.slice(1).trim()
        if (itemText === "") {
          const childIndex = nextContent(index + 1)
          const [child, next] = parseBlock(childIndex, indentation(lines[childIndex]))
          result.push(child)
          index = next
          continue
        }
        if (!itemText.includes(":")) {
          result.push(scalar(itemText))
          index += 1
          continue
        }
        const object = {}
        const [key, valueText] = splitEntry(itemText)
        index += 1
        if (valueText === "") {
          const childIndex = nextContent(index)
          const [child, next] = parseBlock(childIndex, indentation(lines[childIndex]))
          object[key] = child
          index = next
        } else {
          const [value, next] = scalarOrBlock(valueText, index, expectedIndent)
          object[key] = value
          index = next
        }
        while (index < lines.length) {
          index = nextContent(index)
          if (index >= lines.length || indentation(lines[index]) !== expectedIndent + 2
            || lines[index].trim().startsWith("-")) break
          const [nestedKey, nestedText] = splitEntry(lines[index].trim())
          index += 1
          if (nestedText === "") {
            const childIndex = nextContent(index)
            const [child, next] = parseBlock(childIndex, indentation(lines[childIndex]))
            object[nestedKey] = child
            index = next
          } else {
            const [value, next] = scalarOrBlock(nestedText, index, expectedIndent + 2)
            object[nestedKey] = value
            index = next
          }
        }
        result.push(object)
        continue
      }
      if (trimmed.startsWith("-")) break
      const [key, valueText] = splitEntry(trimmed)
      index += 1
      if (valueText === "") {
        const childIndex = nextContent(index)
        if (childIndex >= lines.length || indentation(lines[childIndex]) < expectedIndent) {
          result[key] = null
        } else {
          const [child, next] = parseBlock(childIndex, indentation(lines[childIndex]))
          result[key] = child
          index = next
        }
      } else {
        const [value, next] = scalarOrBlock(valueText, index, expectedIndent)
        result[key] = value
        index = next
      }
    }
    return [result, index]
  }

  const first = nextContent(0)
  if (first >= lines.length) return null
  return parseBlock(first, indentation(lines[first]))[0]
}
