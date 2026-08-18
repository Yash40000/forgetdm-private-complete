'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, DataSourceSchema, Db2ZosLoadProfile, NativeLoaderStatus } from '@/lib/types';

export function useDataSources() {
  return useQuery({
    queryKey: keys.dataSources.all,
    queryFn: () => apiFetch<DataSource[]>('/api/datasources')
  });
}

export function useNativeLoaders() {
  return useQuery({
    queryKey: keys.dataSources.nativeLoaders,
    queryFn: () => apiFetch<NativeLoaderStatus[]>('/api/datasources/native-loaders')
  });
}

export function useDb2ZosLoadProfiles() {
  return useQuery({
    queryKey: [...keys.dataSources.nativeLoaders, 'db2-zos-profiles'],
    queryFn: () => apiFetch<Db2ZosLoadProfile[]>('/api/datasources/db2-zos-loader-profiles')
  });
}

export function dataSourceSchemasPath(dataSourceId: number) {
  return `/api/datasources/${dataSourceId}/schemas`;
}

export type { DataSource, DataSourceSchema, Db2ZosLoadProfile, NativeLoaderStatus };
