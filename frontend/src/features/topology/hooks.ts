'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type {
  ColumnSnapshot,
  DataSourceOption,
  DiscoveryOperation,
  GraphEdge,
  GraphSnapshot,
  SchemaOption,
  SourceBinding,
  TopologySummary,
  TopologyVersion
} from './types';

export function useTopologies() {
  return useQuery({
    queryKey: keys.topology.all,
    queryFn: () => apiFetch<TopologySummary[]>('/api/topologies')
  });
}

export function useTopology(id: number | null) {
  return useQuery({
    queryKey: keys.topology.detail(id),
    queryFn: () => apiFetch<TopologySummary>(`/api/topologies/${id}`),
    enabled: Boolean(id)
  });
}

export function useTopologySources(id: number | null) {
  return useQuery({
    queryKey: keys.topology.sources(id),
    queryFn: () => apiFetch<SourceBinding[]>(`/api/topologies/${id}/sources`),
    enabled: Boolean(id)
  });
}

export function useLatestTopologyDiscovery(id: number | null) {
  return useQuery({
    queryKey: keys.topology.discovery(id),
    queryFn: () => apiFetch<DiscoveryOperation | null>(`/api/topologies/${id}/discovery/latest`),
    enabled: Boolean(id),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'QUEUED' || status === 'RUNNING' ? 1000 : false;
    }
  });
}

export function useTopologyGraph(id: number | null, query: string, sourceBindingId: number | null) {
  const params = new URLSearchParams();
  if (query.trim()) params.set('q', query.trim());
  if (sourceBindingId) params.set('sourceBindingId', String(sourceBindingId));
  params.set('limit', '300');
  return useQuery({
    queryKey: keys.topology.graph(id, query, sourceBindingId),
    queryFn: () => apiFetch<GraphSnapshot>(`/api/topologies/${id}/graph?${params.toString()}`),
    enabled: Boolean(id)
  });
}

export function useTopologyVersions(id: number | null) {
  return useQuery({
    queryKey: keys.topology.versions(id),
    queryFn: () => apiFetch<TopologyVersion[]>(`/api/topologies/${id}/versions`),
    enabled: Boolean(id)
  });
}

export function useNodeColumns(topologyId: number | null, nodeId: number | null) {
  return useQuery({
    queryKey: keys.topology.columns(topologyId, nodeId),
    queryFn: () => apiFetch<ColumnSnapshot[]>(`/api/topologies/${topologyId}/nodes/${nodeId}/columns`),
    enabled: Boolean(topologyId && nodeId)
  });
}

export function useDataSourceOptions() {
  return useQuery({
    queryKey: keys.dataSources.all,
    queryFn: () => apiFetch<DataSourceOption[]>('/api/datasources')
  });
}

export function useSchemaOptions(dataSourceId: number | null) {
  return useQuery({
    queryKey: keys.dataSources.schemas(dataSourceId),
    queryFn: () => apiFetch<SchemaOption[]>(`/api/datasources/${dataSourceId}/schemas`),
    enabled: Boolean(dataSourceId)
  });
}

export function useTopologyActions() {
  const client = useQueryClient();
  const refresh = async (id?: number | null) => {
    await client.invalidateQueries({ queryKey: keys.topology.all });
    if (id) {
      await Promise.all([
        client.invalidateQueries({ queryKey: keys.topology.detail(id) }),
        client.invalidateQueries({ queryKey: keys.topology.sources(id) }),
        client.invalidateQueries({ queryKey: keys.topology.discovery(id) }),
        client.invalidateQueries({ queryKey: keys.topology.versions(id) })
      ]);
      await client.invalidateQueries({ queryKey: ['topology', id, 'graph'] });
    }
  };

  const create = useMutation({
    mutationFn: (body: { name: string; domain?: string; description?: string; visibility: string }) =>
      apiPost<TopologySummary>('/api/topologies', body),
    onSuccess: (created) => refresh(created.id)
  });
  const createSample = useMutation({
    mutationFn: () =>
      apiPost<{ topology: TopologySummary; discovery: DiscoveryOperation }>('/api/topologies/sample', {}),
    onSuccess: (sample) => refresh(sample.topology.id)
  });
  const remove = useMutation({
    mutationFn: (id: number) => apiFetch<void>(`/api/topologies/${id}`, { method: 'DELETE' }),
    onSuccess: () => refresh()
  });
  const attachSource = useMutation({
    mutationFn: ({ id, ...body }: { id: number; dataSourceId: number; schemaName: string; applicationLabel?: string }) =>
      apiPost<SourceBinding>(`/api/topologies/${id}/sources`, body),
    onSuccess: (binding) => refresh(binding.topologyId)
  });
  const detachSource = useMutation({
    mutationFn: ({ id, bindingId }: { id: number; bindingId: number }) =>
      apiFetch<void>(`/api/topologies/${id}/sources/${bindingId}`, { method: 'DELETE' }),
    onSuccess: (_result, variables) => refresh(variables.id)
  });
  const discover = useMutation({
    mutationFn: (id: number) => apiPost<DiscoveryOperation>(`/api/topologies/${id}/discover`, {}),
    onSuccess: (operation) => refresh(operation.topologyId)
  });
  const cancelDiscovery = useMutation({
    mutationFn: ({ id, operationId }: { id: number; operationId: number }) =>
      apiPost<DiscoveryOperation>(`/api/topologies/${id}/discovery/${operationId}/cancel`, {}),
    onSuccess: (operation) => refresh(operation.topologyId)
  });
  const reviewEdge = useMutation({
    mutationFn: ({ id, edgeId, status }: { id: number; edgeId: number; status: string }) =>
      apiPut<GraphEdge>(`/api/topologies/${id}/edges/${edgeId}`, { status }),
    onSuccess: (_edge, variables) => refresh(variables.id)
  });

  return { create, createSample, remove, attachSource, detachSource, discover, cancelDiscovery, reviewEdge };
}
