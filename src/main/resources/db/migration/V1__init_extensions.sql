-- TRD 11-1: 상담 가능 시간 겹침 금지(exclude using gist)에 필요
create extension if not exists btree_gist;
