-- venues table
alter table venues add column created_at timestamp not null default now();
alter table venues add column updated_at timestamp not null default now();
alter table venues alter column created_at drop default;
alter table venues alter column updated_at drop default;

-- seats table
alter table seats add column created_at timestamp not null default now();
alter table seats add column updated_at timestamp not null default now();
alter table seats alter column created_at drop default;
alter table seats alter column updated_at drop default;

-- event_seats table
alter table event_seats add column created_at timestamp not null default now();
alter table event_seats add column updated_at timestamp not null default now();
alter table event_seats alter column created_at drop default;
alter table event_seats alter column updated_at drop default;

-- reservations table
alter table reservations add column created_at timestamp not null default now();
alter table reservations add column updated_at timestamp not null default now();
alter table reservations alter column created_at drop default;
alter table reservations alter column updated_at drop default;

-- reservation_seats table
alter table reservation_seats add column created_at timestamp not null default now();
alter table reservation_seats add column updated_at timestamp not null default now();
alter table reservation_seats alter column created_at drop default;
alter table reservation_seats alter column updated_at drop default;
