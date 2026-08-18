-- Administrators operate pre-approved self-service requests. Maker-checker remains mandatory for
-- non-admin requesters when the published product requires approval.
UPDATE self_service_orders o
   SET status = 'APPROVED',
       decision_by = COALESCE(decision_by, 'SYSTEM'),
       decision_note = COALESCE(decision_note, 'Administrator request: approval bypassed by policy'),
       decided_at = COALESCE(decided_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP
 WHERE o.status = 'PENDING_APPROVAL'
   AND (
       EXISTS (
           SELECT 1
             FROM forge_user_roles r
            WHERE r.user_id = o.requested_by_id
              AND r.role_name = 'ADMIN'
       )
       OR EXISTS (
           SELECT 1
             FROM forge_user_groups ug
             JOIN forge_group_roles gr ON gr.group_id = ug.group_id
            WHERE ug.user_id = o.requested_by_id
              AND gr.role_name = 'ADMIN'
       )
   );

INSERT INTO self_service_order_events(order_id, event_type, actor, message, detail_json, created_at)
SELECT o.id, 'APPROVED', 'SYSTEM', 'Administrator request: approval bypassed by policy',
       '{"approvalMode":"ADMIN_BYPASS"}', CURRENT_TIMESTAMP
  FROM self_service_orders o
 WHERE o.status = 'APPROVED'
   AND o.decision_by = 'SYSTEM'
   AND o.decision_note = 'Administrator request: approval bypassed by policy'
   AND NOT EXISTS (
       SELECT 1 FROM self_service_order_events e
        WHERE e.order_id = o.id
          AND e.event_type = 'APPROVED'
          AND e.actor = 'SYSTEM'
   );
