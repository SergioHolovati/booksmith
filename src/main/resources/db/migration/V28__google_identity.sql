-- Vínculo com o Google pela identidade da conta Google (o "sub", que não muda), não só pelo e-mail: quem se
-- cadastrou com senha pode ligar uma conta Google de outro e-mail pelo perfil.
alter table identity.users add column google_subject text;
alter table identity.users add column google_email text;
create unique index users_google_subject_uk on identity.users (google_subject) where google_subject is not null;
