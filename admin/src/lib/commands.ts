/* Search for the command palette: pages and actions matched by label and keywords. Words that start with what was
   typed rank first, then anywhere in a word, then the letters in order ("rtn" finds Retention). */

export interface Command {
  id: string;
  label: string;
  group: "Pages" | "Actions" | "People";
  keywords?: string[];
  hint?: string;
}

const normalize = (text: string) =>
  text
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .trim();

/** Higher is better; 0 means no match. */
export function scoreCommand(query: string, command: Command): number {
  const q = normalize(query);
  if (!q) return 1;
  const haystacks = [command.label, ...(command.keywords ?? [])].map(normalize);
  let best = 0;
  for (const [index, text] of haystacks.entries()) {
    const weight = index === 0 ? 1 : 0.8;
    if (text === q) best = Math.max(best, 100 * weight);
    else if (text.startsWith(q)) best = Math.max(best, 80 * weight);
    else if (text.split(/[\s/-]+/).some((word) => word.startsWith(q))) best = Math.max(best, 60 * weight);
    else if (text.includes(q)) best = Math.max(best, 40 * weight);
    else if (subsequence(q.replace(/\s+/g, ""), text)) best = Math.max(best, 15 * weight);
  }
  return best;
}

function subsequence(needle: string, text: string): boolean {
  let at = 0;
  for (const char of text) if (char === needle[at]) at++;
  return at === needle.length;
}

/** The matching commands, best first; ties keep their original order. */
export function searchCommands(query: string, commands: Command[], limit = 12): Command[] {
  return commands
    .map((command, index) => ({ command, index, score: scoreCommand(query, command) }))
    .filter((item) => item.score > 0)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .slice(0, limit)
    .map((item) => item.command);
}

/** Whether a query is worth sending to the people search: two characters, or anything that looks like an id. */
export const searchesPeople = (query: string) => query.trim().length >= 2;
