import { flexRender, getCoreRowModel, useReactTable, type ColumnDef } from "@tanstack/react-table";
import type { ReactNode } from "react";
import { Empty, Skeleton } from "./ui";

declare module "@tanstack/react-table" {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface ColumnMeta<TData, TValue> {
    numeric?: boolean;
    /** Hidden on phone-width screens, where only the essentials fit. */
    secondary?: boolean;
  }
}

function cellClass(meta: { numeric?: boolean; secondary?: boolean } | undefined) {
  return [meta?.numeric ? "num" : "", meta?.secondary ? "secondary" : ""].join(" ").trim() || undefined;
}

/**
 * Server-paged table: the API filters and pages, TanStack Table renders the columns. Rows can open
 * a detail page; Enter on a focused row does the same.
 */
export function DataTable<T>({
  data,
  columns,
  loading,
  empty = "Nothing matches.",
  onRowClick,
  rowLabel,
  getRowId,
}: {
  data: T[] | undefined;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  columns: ColumnDef<T, any>[];
  loading?: boolean;
  empty?: ReactNode;
  onRowClick?: (row: T) => void;
  rowLabel?: (row: T) => string;
  getRowId?: (row: T) => string;
}) {
  const table = useReactTable({ data: data ?? [], columns, getCoreRowModel: getCoreRowModel(), getRowId, manualPagination: true });

  return (
    <div className="table-wrap">
      <table className="data">
        <thead>
          {table.getHeaderGroups().map((group) => (
            <tr key={group.id}>
              {group.headers.map((header) => (
                <th key={header.id} className={cellClass(header.column.columnDef.meta)} scope="col">
                  {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
                </th>
              ))}
            </tr>
          ))}
        </thead>
        <tbody>
          {loading && !data
            ? Array.from({ length: 6 }, (_, index) => (
                <tr key={index}>
                  {columns.map((_, cell) => (
                    <td key={cell}>
                      <Skeleton height={16} width={cell === 0 ? "80%" : "60%"} />
                    </td>
                  ))}
                </tr>
              ))
            : table.getRowModel().rows.map((row) => (
                <tr
                  key={row.id}
                  className={onRowClick ? "link" : undefined}
                  tabIndex={onRowClick ? 0 : undefined}
                  aria-label={onRowClick && rowLabel ? rowLabel(row.original) : undefined}
                  onClick={onRowClick ? () => onRowClick(row.original) : undefined}
                  onKeyDown={
                    onRowClick
                      ? (event) => {
                          if (event.key === "Enter") onRowClick(row.original);
                        }
                      : undefined
                  }
                >
                  {row.getVisibleCells().map((cell) => (
                    <td key={cell.id} className={cellClass(cell.column.columnDef.meta)}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  ))}
                </tr>
              ))}
        </tbody>
      </table>
      {!loading && data && data.length === 0 ? <Empty>{empty}</Empty> : null}
    </div>
  );
}
