'use client';

import { ActionIcon, Badge, Group, Menu, Text, Tooltip } from '@mantine/core';
import {
  IconArrowRight,
  IconDots,
  IconNetwork,
  IconTrash
} from '@tabler/icons-react';

import type { TopologySummary } from '../types';

export function TopologyCatalog({
  topologies,
  onOpen,
  onDelete
}: {
  topologies: TopologySummary[];
  onOpen: (topology: TopologySummary) => void;
  onDelete: (topology: TopologySummary) => void;
}) {
  return (
    <div className="topology-catalog" role="list">
      <div className="topology-catalog-head" aria-hidden="true">
        <span>Topology</span>
        <span>Coverage</span>
        <span>Version</span>
        <span>Updated</span>
        <span />
      </div>
      {topologies.map((topology) => (
        <div className="topology-catalog-row" role="listitem" key={topology.id}>
          <button className="topology-catalog-main" type="button" onClick={() => onOpen(topology)}>
            <span className="topology-catalog-icon">
              <IconNetwork size={18} />
            </span>
            <span className="topology-catalog-copy">
              <span className="topology-catalog-name">
                {topology.name}
                <Badge size="xs" variant="light" color={topology.status === 'ACTIVE' ? 'green' : 'gray'}>
                  {topology.status}
                </Badge>
              </span>
              <Text component="span" size="xs" c="dimmed" truncate>
                {topology.domain || 'Unassigned domain'}
                {topology.description ? ` · ${topology.description}` : ''}
              </Text>
            </span>
          </button>
          <div className="topology-catalog-coverage">
            <strong>{topology.sourceCount}</strong>
            <span>sources</span>
            <strong>{topology.nodeCount}</strong>
            <span>objects</span>
            <strong>{topology.edgeCount}</strong>
            <span>links</span>
          </div>
          <div className="topology-catalog-version">
            <Text fw={700} size="sm">
              {topology.currentVersion > 0 ? `v${topology.currentVersion}` : 'Draft'}
            </Text>
            <Text size="xs" c="dimmed">
              {topology.currentHash ? topology.currentHash.slice(0, 10) : 'Not captured'}
            </Text>
          </div>
          <div>
            <Text size="sm">{relativeTime(topology.updatedAt)}</Text>
            <Text size="xs" c="dimmed">
              {topology.ownerUsername || 'System'}
            </Text>
          </div>
          <Group gap={4} justify="flex-end" wrap="nowrap">
            <Tooltip label="Open workspace">
              <ActionIcon variant="subtle" aria-label={`Open ${topology.name}`} onClick={() => onOpen(topology)}>
                <IconArrowRight size={18} />
              </ActionIcon>
            </Tooltip>
            <Menu position="bottom-end" withinPortal>
              <Menu.Target>
                <ActionIcon variant="subtle" color="gray" aria-label={`Actions for ${topology.name}`}>
                  <IconDots size={18} />
                </ActionIcon>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item color="red" leftSection={<IconTrash size={15} />} onClick={() => onDelete(topology)}>
                  Delete topology
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
        </div>
      ))}
    </div>
  );
}

function relativeTime(value: string) {
  const time = new Date(value).getTime();
  const elapsed = Date.now() - time;
  if (!Number.isFinite(elapsed)) return 'Unknown';
  const minutes = Math.max(0, Math.floor(elapsed / 60_000));
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}
