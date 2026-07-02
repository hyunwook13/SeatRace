alter table seats
    add column if not exists seat_section_id bigint;

alter table seats
    add column if not exists seat_number integer;

alter table seats
    alter column venue_id drop not null;

alter table seats
    alter column section drop not null;

alter table seats
    alter column seat_no drop not null;

alter table seats
    alter column grade drop not null;

alter table seats
    alter column price drop not null;

update seats s
set seat_section_id = ss.id
from seat_sections ss
where s.seat_section_id is null
  and ss.venue_id = s.venue_id
  and ss.name = s.section;

update seats
set seat_number = case
    when seat_no ~ '^[0-9]+$' then seat_no::integer
    else null
end
where seat_number is null;

alter table seats
    add constraint fk_seats_seat_section
        foreign key (seat_section_id) references seat_sections(id);

create unique index if not exists uq_seats_section_row_number
    on seats (seat_section_id, row_no, seat_number);
