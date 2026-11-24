-- Atendimento no backoffice (§20.6): a conversa de cada pedido. A primeira mensagem continua em requests.message;
-- aqui ficam as respostas da equipe, as respostas da pessoa e as notas internas (só a equipe vê).
create table support.messages (
    id          uuid primary key,
    request_id  uuid        not null references support.requests (id) on delete cascade,
    author      text        not null,          -- user | staff
    author_id   uuid        not null,          -- referência lógica a identity.users
    body        text        not null,
    internal    boolean     not null default false,
    created_at  timestamptz not null default now()
);

create index messages_request_idx on support.messages (request_id, created_at);

-- Situações do atendimento: open (esperando a equipe) | waiting_user (respondido, esperando a pessoa) | resolved
update support.requests set status = 'waiting_user' where status = 'answered';
update support.requests set status = 'resolved' where status = 'closed';

-- Para a caixa de entrada ordenar pelo que mexeu por último
alter table support.requests add column last_activity_at timestamptz not null default now();
update support.requests set last_activity_at = created_at;

drop index if exists support.requests_open_idx;
create index requests_inbox_idx on support.requests (status, last_activity_at desc);
