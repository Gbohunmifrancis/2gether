import { Link } from "@tanstack/react-router";

export function NotFound() {
  return (
    <div className="auth">
      <div className="card">
        <h1 className="headline">Not here</h1>
        <p className="muted" style={{ margin: "8px 0 20px" }}>
          That page doesn't exist.
        </p>
        <Link to="/" className="btn filled">
          Back to the overview
        </Link>
      </div>
    </div>
  );
}
