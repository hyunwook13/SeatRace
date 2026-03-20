alter table users
    add constraint uq_users_email unique (email);

alter table venues
    add constraint uq_venues_location unique (location);
