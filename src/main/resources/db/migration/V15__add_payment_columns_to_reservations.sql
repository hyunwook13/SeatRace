alter table reservations
    add column if not exists payment_order_id varchar(100);

alter table reservations
    add column if not exists payment_key varchar(200);

alter table reservations
    add column if not exists payment_amount bigint;

alter table reservations
    add column if not exists payment_status varchar(20);

alter table reservations
    add column if not exists payment_failure_reason varchar(500);

alter table reservations
    add column if not exists paid_at timestamp;

create unique index if not exists uq_reservations_payment_order_id
    on reservations (payment_order_id);
