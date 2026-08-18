export type ExplorerColumn = {
  column: string;
  type?: string | null;
  jdbcType?: number | null;
  size?: number | null;
  scale?: number | null;
  ordinal?: number | null;
  nullable?: boolean;
  defaultValue?: string | null;
  autoIncrement?: boolean;
  generated?: boolean;
};

export type ExplorerTableResult = {
  dataSourceId: number;
  schema?: string | null;
  table: string;
  columns: ExplorerColumn[];
  primaryKeys: string[];
  editable: boolean;
  rows: Array<Record<string, unknown>>;
  rowCount: number;
  offset: number;
  limit: number;
  hasMore: boolean;
  elapsedMs: number;
};

export type QueryResult = {
  columns: string[];
  rows: unknown[][];
  rowCount: number;
  affectedRows?: number;
  statementType?: string;
  truncated: boolean;
  elapsedMs: number;
};

export type MutationResult = {
  action: 'INSERT' | 'UPDATE' | 'DELETE';
  affectedRows: number;
  schema?: string | null;
  table: string;
};

export type TableIdentity = {
  dataSourceId: number;
  schema?: string | null;
  table: string;
};
