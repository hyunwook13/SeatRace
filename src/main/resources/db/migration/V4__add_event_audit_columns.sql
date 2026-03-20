alter table events
    add column created_at timestamp not null default now();

alter table events
    add column updated_at timestamp not null default now();

alter table events
    alter column created_at drop default;

alter table events
    alter column updated_at drop default;