-- Planos (§17 da arquitetura): o plano de cada conta que não está no padrão. Sem linha (ou vencida), vale o plano
-- padrão (booksmith.billing.default-plan: Autor durante o beta; Grátis quando o cadastro abrir).
create schema if not exists billing;

create table billing.subscriptions (
    user_id     uuid primary key,              -- referência lógica a identity.users
    plan        text        not null,          -- GRATIS | ESCRITOR | AUTOR
    source      text        not null,          -- courtesy (cortesia) | founder (fundador, pago por link) | asaas
    founder     boolean     not null default false,
    expires_at  timestamptz,                   -- null: sem prazo
    note        text,
    granted_by  uuid,                          -- funcionário que concedeu (identity.staff_members)
    granted_at  timestamptz not null default now()
);
