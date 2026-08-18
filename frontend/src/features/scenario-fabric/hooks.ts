'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type {
  Blueprint,
  DomainAsset,
  DomainDetail,
  DomainSummary,
  Mission,
  MissionDraft,
  SelfServiceProduct
} from './types';

export function useScenarioDomains() {
  return useQuery({
    queryKey: keys.scenarioFabric.domains,
    queryFn: () => apiFetch<DomainSummary[]>('/api/scenario-fabric/domains')
  });
}

export function useScenarioDomain(id: number | null) {
  return useQuery({
    queryKey: keys.scenarioFabric.domain(id),
    queryFn: () => apiFetch<DomainDetail>(`/api/scenario-fabric/domains/${id}`),
    enabled: Boolean(id)
  });
}

export function useScenarioBlueprints(domainId?: number | null) {
  const suffix = domainId ? `?domainId=${domainId}` : '';
  return useQuery({
    queryKey: keys.scenarioFabric.blueprints(domainId),
    queryFn: () => apiFetch<Blueprint[]>(`/api/scenario-fabric/blueprints${suffix}`)
  });
}

export function useScenarioMissions() {
  return useQuery({
    queryKey: keys.scenarioFabric.missions,
    queryFn: () => apiFetch<Mission[]>('/api/scenario-fabric/missions'),
    refetchInterval: (query) =>
      (query.state.data || []).some((mission) =>
        ['WAITING_APPROVAL', 'APPROVED', 'RUNNING'].includes(mission.status))
        ? 3000
        : false
  });
}

export function useScenarioMission(id: string | null) {
  return useQuery({
    queryKey: keys.scenarioFabric.mission(id),
    queryFn: () => apiFetch<Mission>(`/api/scenario-fabric/missions/${id}`),
    enabled: Boolean(id),
    refetchInterval: (query) =>
      query.state.data &&
      ['WAITING_APPROVAL', 'APPROVED', 'RUNNING'].includes(query.state.data.status)
        ? 2000
        : false
  });
}

export function useScenarioProducts() {
  return useQuery({
    queryKey: keys.selfService.enterpriseCatalog,
    queryFn: () => apiFetch<SelfServiceProduct[]>('/api/self-service/v2/catalog')
  });
}

export function useScenarioActions() {
  const client = useQueryClient();
  const refresh = async (domainId?: number | null, missionId?: string | null) => {
    await Promise.all([
      client.invalidateQueries({ queryKey: keys.scenarioFabric.domains }),
      client.invalidateQueries({ queryKey: keys.scenarioFabric.blueprints() }),
      client.invalidateQueries({ queryKey: keys.scenarioFabric.missions })
    ]);
    if (domainId) {
      await Promise.all([
        client.invalidateQueries({ queryKey: keys.scenarioFabric.domain(domainId) }),
        client.invalidateQueries({ queryKey: keys.scenarioFabric.blueprints(domainId) })
      ]);
    }
    if (missionId) {
      await client.invalidateQueries({ queryKey: keys.scenarioFabric.mission(missionId) });
    }
  };

  const publishDomain = useMutation({
    mutationFn: (body: {
      topologyId: number;
      name: string;
      businessDomain?: string;
      description?: string;
      visibility: string;
      createStarterBlueprint: boolean;
    }) => apiPost<DomainDetail>('/api/scenario-fabric/domains', body),
    onSuccess: (result) => refresh(result.summary.id)
  });

  const bindProduct = useMutation({
    mutationFn: ({ domainId, productId }: { domainId: number; productId: string }) =>
      apiPost<DomainAsset>(`/api/scenario-fabric/domains/${domainId}/assets`, {
        assetType: 'SELF_SERVICE_PRODUCT',
        artifactId: productId,
        assetRole: 'EXECUTION',
        required: true,
        config: {}
      }),
    onSuccess: (asset) => refresh(asset.domainId)
  });

  const unbindProduct = useMutation({
    mutationFn: ({ domainId, assetId }: { domainId: number; assetId: number }) =>
      apiFetch<void>(`/api/scenario-fabric/domains/${domainId}/assets/${assetId}`, {
        method: 'DELETE'
      }),
    onSuccess: (_result, input) => refresh(input.domainId)
  });

  const createBlueprint = useMutation({
    mutationFn: ({ domainId, body }: { domainId: number; body: Record<string, unknown> }) =>
      apiPost<Blueprint>(`/api/scenario-fabric/domains/${domainId}/blueprints`, body),
    onSuccess: (blueprint) => refresh(blueprint.domainId)
  });

  const loadValidationExamples = useMutation({
    mutationFn: (domainId: number) =>
      apiPost<Blueprint[]>(`/api/scenario-fabric/domains/${domainId}/validation-examples`, {}),
    onSuccess: (blueprints) => refresh(blueprints[0]?.domainId)
  });

  const updateBlueprint = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Record<string, unknown> }) =>
      apiPut<Blueprint>(`/api/scenario-fabric/blueprints/${id}`, body),
    onSuccess: (blueprint) => refresh(blueprint.domainId)
  });

  const createMission = useMutation({
    mutationFn: (draft: MissionDraft) =>
      apiPost<Mission>('/api/scenario-fabric/missions', draft),
    onSuccess: (mission) => refresh(mission.domainId, mission.id)
  });

  const launchMission = useMutation({
    mutationFn: (id: string) =>
      apiPost<Mission>(`/api/scenario-fabric/missions/${id}/launch`, {}),
    onSuccess: (mission) => refresh(mission.domainId, mission.id)
  });

  const refreshMission = useMutation({
    mutationFn: (id: string) =>
      apiPost<Mission>(`/api/scenario-fabric/missions/${id}/refresh`, {}),
    onSuccess: (mission) => refresh(mission.domainId, mission.id)
  });

  return {
    publishDomain,
    bindProduct,
    unbindProduct,
    createBlueprint,
    loadValidationExamples,
    updateBlueprint,
    createMission,
    launchMission,
    refreshMission
  };
}
