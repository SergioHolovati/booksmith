-- Pedidos de suporte (§20.6 da arquitetura). A mensagem do usuário e um contexto técnico coletado pelo app
-- (tela, aparelho, rede, últimos erros), nunca o conteúdo do manuscrito. O ticket na ferramenta de
-- atendimento (helpdesk_ref) é criado pelo HelpdeskGateway quando a ferramenta for escolhida.
create schema if not exists support;

create table support.requests (
    id            uuid primary key,
    user_id       uuid        not null,          -- referência lógica a identity.users
    email         text        not null,          -- para a resposta, mesmo que a conta mude depois
    category      text        not null,          -- lost_text | account | import_export | universe | bug | suggestion | privacy | other
    message       text        not null,
    context       jsonb       not null default '{}',
    priority      text        not null,          -- high | normal
    status        text        not null default 'open',  -- open | answered | closed
    helpdesk_ref  text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index requests_user_idx on support.requests (user_id, created_at desc);
create index requests_open_idx on support.requests (priority, created_at) where status = 'open';
