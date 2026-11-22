import fs from "fs";
import path from "path";

export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
}

const IGNORED_DIRS = new Set([
  "node_modules",
  ".git",
  ".gradle",
  "build",
  "dist",
  ".idea",
  "__pycache__",
  ".next",
  ".venv",
  "venv",
  ".vs",
  "target",
  "obj",
]);

function resolveSafePath(workspaceRoot: string, relativePath: string): string {
  const normalizedRoot = path.resolve(workspaceRoot);
  const target = path.resolve(normalizedRoot, relativePath || ".");
  if (
    target !== normalizedRoot &&
    !target.startsWith(normalizedRoot + path.sep)
  ) {
    throw new Error("Access denied: path is outside active workspace");
  }
  return target;
}

export function listDirectory(workspaceRoot: string, relativePath = ""): FileEntry[] {
  const targetDir = resolveSafePath(workspaceRoot, relativePath);
  if (!fs.existsSync(targetDir) || !fs.statSync(targetDir).isDirectory()) {
    throw new Error("Directory not found");
  }

  const dirents = fs.readdirSync(targetDir, { withFileTypes: true });
  const results: FileEntry[] = [];

  for (const dirent of dirents) {
    if (dirent.isDirectory() && IGNORED_DIRS.has(dirent.name)) {
      continue;
    }
    const fullPath = path.join(targetDir, dirent.name);
    const relPath = path.relative(workspaceRoot, fullPath).replace(/\\/g, "/");
    let size = 0;
    if (!dirent.isDirectory()) {
      try {
        size = fs.statSync(fullPath).size;
      } catch {
        size = 0;
      }
    }

    results.push({
      name: dirent.name,
      path: relPath,
      isDirectory: dirent.isDirectory(),
      size,
    });
  }

  results.sort((a, b) => {
    if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
    return a.name.localeCompare(b.name);
  });

  return results;
}

export function readFileContent(
  workspaceRoot: string,
  relativePath: string
): { path: string; content: string; truncated: boolean; size: number } {
  const targetFile = resolveSafePath(workspaceRoot, relativePath);
  if (!fs.existsSync(targetFile) || fs.statSync(targetFile).isDirectory()) {
    throw new Error("File not found");
  }

  const stat = fs.statSync(targetFile);
  const maxBytes = 256 * 1024; // 256 KB limit for mobile preview
  const truncated = stat.size > maxBytes;

  const fd = fs.openSync(targetFile, "r");
  try {
    const readLen = Math.min(stat.size, maxBytes);
    const buffer = Buffer.alloc(readLen);
    fs.readSync(fd, buffer, 0, readLen, 0);
    // Detect binary files via null bytes
    if (buffer.includes(0)) {
      return {
        path: relativePath,
        content: "[Binary file cannot be displayed as text]",
        truncated: false,
        size: stat.size,
      };
    }
    return {
      path: relativePath,
      content: buffer.toString("utf-8"),
      truncated,
      size: stat.size,
    };
  } finally {
    fs.closeSync(fd);
  }
}
