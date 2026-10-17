-- Public site uses one curated JSON snapshot instead of exposing local app tables.
create table if not exists public.public_site_snapshot (
    id smallint primary key check (id = 1),
    snapshot jsonb not null,
    published_at timestamptz not null default now()
);

alter table public.public_site_snapshot enable row level security;

revoke all on table public.public_site_snapshot from anon, authenticated;
grant select on table public.public_site_snapshot to anon, authenticated;
grant insert, update on table public.public_site_snapshot to authenticated;

drop policy if exists "Anyone can read the published snapshot" on public.public_site_snapshot;
create policy "Anyone can read the published snapshot"
    on public.public_site_snapshot
    for select
    to anon, authenticated
    using (id = 1);

drop policy if exists "Publishers can create the public snapshot" on public.public_site_snapshot;
create policy "Publishers can create the public snapshot"
    on public.public_site_snapshot
    for insert
    to authenticated
    with check (
        id = 1
        and (auth.jwt() -> 'app_metadata' ->> 'role') = 'publisher'
    );

drop policy if exists "Publishers can update the public snapshot" on public.public_site_snapshot;
create policy "Publishers can update the public snapshot"
    on public.public_site_snapshot
    for update
    to authenticated
    using (
        id = 1
        and (auth.jwt() -> 'app_metadata' ->> 'role') = 'publisher'
    )
    with check (
        id = 1
        and (auth.jwt() -> 'app_metadata' ->> 'role') = 'publisher'
    );
