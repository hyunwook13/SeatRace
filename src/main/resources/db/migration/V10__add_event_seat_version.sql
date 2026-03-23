alter table event_seats
    add column version bigint not null default 0;

alter table event_seats
    alter column version drop default;
