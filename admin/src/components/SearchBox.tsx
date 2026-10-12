import { useEffect, useRef, useState } from "react";

/** A search field that reports after typing pauses, so each keystroke isn't a request. */
export function SearchBox({
  value,
  placeholder,
  onSearch,
  delay = 350,
}: {
  value?: string;
  placeholder: string;
  onSearch: (value: string | undefined) => void;
  delay?: number;
}) {
  const [text, setText] = useState(value ?? "");
  const latest = useRef(onSearch);
  latest.current = onSearch;
  const first = useRef(true);

  // The URL changed from elsewhere (back button, a link): show it.
  useEffect(() => setText(value ?? ""), [value]);

  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    const next = text.trim() || undefined;
    if (next === (value ?? undefined)) return;
    const timer = setTimeout(() => latest.current(next), delay);
    return () => clearTimeout(timer);
  }, [text, value, delay]);

  return (
    <label style={{ display: "contents" }}>
      <span className="visually-hidden">Search</span>
      <input className="input" type="search" placeholder={placeholder} value={text} onChange={(event) => setText(event.target.value)} spellCheck={false} />
    </label>
  );
}
