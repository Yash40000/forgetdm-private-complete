'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import type { TdRecipe, TdRequestView } from './types';

const BASE = '/api/test-data';

export function useRecipes() {
  return useQuery({ queryKey: ['td', 'recipes'], queryFn: () => apiFetch<TdRecipe[]>(`${BASE}/recipes`) });
}

export function useMyRequests() {
  return useQuery({ queryKey: ['td', 'requests'], queryFn: () => apiFetch<TdRequestView[]>(`${BASE}/requests`) });
}

export function useTdMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: ['td', 'requests'] });

  const create = useMutation({
    mutationFn: (body: { request: string; environment?: string; quantity?: number; purpose?: string }) =>
      apiPost<TdRequestView>(`${BASE}/requests`, body),
    onSuccess: invalidate
  });
  const confirm = useMutation({
    mutationFn: (id: number) => apiPost<TdRequestView>(`${BASE}/requests/${id}/confirm`, {}),
    onSuccess: invalidate
  });
  const reserve = useMutation({
    mutationFn: (v: { id: number; purpose?: string }) =>
      apiPost<TdRequestView>(`${BASE}/requests/${v.id}/reserve`, { purpose: v.purpose }),
    onSuccess: invalidate
  });
  const teardown = useMutation({
    mutationFn: (id: number) => apiPost<TdRequestView>(`${BASE}/requests/${id}/teardown`, {}),
    onSuccess: invalidate
  });
  const remove = useMutation({
    mutationFn: (id: number) => apiFetch<{ deleted: boolean }>(`${BASE}/requests/${id}`, { method: 'DELETE' }),
    onSuccess: invalidate
  });

  return { create, confirm, reserve, teardown, remove };
}
