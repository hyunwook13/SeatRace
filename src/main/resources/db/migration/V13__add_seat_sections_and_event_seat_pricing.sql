create table seat_sections (
    id bigserial primary key,
    venue_id bigint not null,
    name varchar(50) not null,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

alter table seat_sections
    add constraint fk_seat_sections_venue
        foreign key (venue_id) references venues(id);

alter table seat_sections
    add constraint uq_seat_sections_venue_name
        unique (venue_id, name);

alter table event_seats
    add column if not exists grade varchar(10);

alter table event_seats
    add column if not exists price bigint;
