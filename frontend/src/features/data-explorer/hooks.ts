'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, DataSourceSchema } from '@/lib/types';
import type { ExplorerTableResult, MutationResult, QueryResult, TableIdentity } from './types';

export function useExplorerCatalog(dataSourceId: number | null, schema: string | null) {
  const dataSources = useQuery({
    queryKey: keys.dataSources.all,
    queryFn: () => apiFetch<DataSource[]>('/api/datasources')
  });
  const schemas = useQuery({
    queryKey: keys.dataSources.schemas(dataSourceId),
    queryFn: () => apiFetch<DataSourceSchema[]>(`/api/datasources/${dataSourceId}/schemas`),
    enabled: Boolean(dataSourceId)
  });
  const tables = useQuery({
    queryKey: keys.dataSources.tables(dataSourceId, schema),
    queryFn: () =>
      apiFetch<Array<{ table: string; schema?: string | null; type?: string | null }>>(
        `/api/datasources/${dataSourceId}/tables?schema=${encodeURIComponent(schema || '')}`
      ),
    enabled: Boolean(dataSourceId && schema)
  });
  return { dataSources, schemas, tables };
}

export function useExplorerTable(identity: TableIdentity | null, offset: number, limit: number) {
  return useQuery({
    queryKey: keys.dataExplorer.table(identity?.dataSourceId, identity?.schema, identity?.table, offset, limit),
    queryFn: () =>
      apiPost<ExplorerTableResult>('/api/query/table/read', {
        ...identity,
        offset,
        limit
      }),
    enabled: Boolean(identity)
  });
}

export function useExplorerMutations(identity: TableIdentity | null, selectedDataSourceId?: number | null) {
  const queryClient = useQueryClient();
  const activeDataSourceId = identity?.dataSourceId ?? selectedDataSourceId;
  const invalidate = () =>
    queryClient.invalidateQueries({
      queryKey: ['data-explorer', identity?.dataSourceId || '', identity?.schema || '', identity?.table || '']
    });

  const insert = useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      apiPost<MutationResult>('/api/query/table/insert', { ...identity, values }),
    onSuccess: invalidate
  });
  const update = useMutation({
    mutationFn: (payload: { keyValues: Record<string, unknown>; values: Record<string, unknown> }) =>
      apiPost<MutationResult>('/api/query/table/update', { ...identity, ...payload }),
    onSuccess: invalidate
  });
  const remove = useMutation({
    mutationFn: (keyValues: Record<string, unknown>) =>
      apiPost<MutationResult>('/api/query/table/delete', { ...identity, keyValues }),
    onSuccess: invalidate
  });
  const runSql = useMutation({
    mutationFn: (sql: string) =>
      apiPost<QueryResult>('/api/query/run', { dataSourceId: activeDataSourceId, sql })
  });
  const executeSql = useMutation({
    mutationFn: (sql: string) =>
      apiPost<QueryResult>('/api/query/execute', { dataSourceId: activeDataSourceId, sql }),
    onSuccess: invalidate
  });
  return { insert, update, remove, runSql, executeSql };
}
