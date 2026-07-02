alter table seats
    add column price bigint not null default 0;

alter table seats
    alter column price drop default;
