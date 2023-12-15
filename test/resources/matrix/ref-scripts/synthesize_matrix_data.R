library(Seurat)
library(data.table)
library(dplyr)
library(reshape2)
library(Matrix)
library(wick)

pbmc <- Read10X(data.dir = "~/data/filtered_gene_bc_matrices/hg19/")
mtx <- pbmc

# the assay data is here:
# pbmc@assays$RNA@counts
# class(pbmc@assays$RNA@counts)

# see https://cran.r-project.org/web/packages/Matrix/Matrix.pdf for more info
# on fields for dgCMatrix, inherited from CMatrix
mtx@x  # numeric field (matrix proper)
mtx@i  # confusingly, these are 0-based row number ndices
mtx@p  # also 0-based, pointers to columns
mtx@Dimnames[[1]]
mtx@Dimnames[[2]]

# this changes representation to more standardized triple form dgTMatrix
# (from compressed form) dgCMatrix
triple.mtx <- as (mtx, "dgTMatrix")
rows <- triple.mtx
# i and j are still 0 based indices into normal R collections specified
# in dim names (so that means we need to add 1 to get proper indexing.)

as.triples <- function(m) {
    row.names <- m@Dimnames[[2]] # not sure if Seurat always guarantees this
    col.names <- m@Dimnames[[1]] # Dimname positioning for 10x matrix, always inspect.
    gene = col.names[m@i + 1]  # Matrix package formats use 0 based indexing.
    cell = row.names[m@j + 1]  # probably b/c wrapping native code.
    data.frame(barcode=cell, hugo=gene, count=m@x)
}

out <- as.triples(triple.mtx)
head(out)

# can check alignment this way:
ref.barcode <- out$barcode[1]
src <- triple.mtx[,ref.barcode]
dest <- out[out$barcode == ref.barcode,]
head(triple.mtx)

# any gene selected should match
gene <- "JUN"
src[gene]
dest[dest$hugo == gene,]
# example:
# JUN
#  11
#             barcode hugo count
# 30 AAACATACAACCAC-1  JUN    11

# With transform verified, write into the correct format for CANDEL this way.
melted <- as.triples(triple.mtx)
write.table(melted, "~/data/processed-counts.tsv",
            row.names = FALSE,
            col.names = TRUE,
            quote = FALSE,
            sep = "\t")

## TEST ONLY ##
# make a bad matrix file
melted$unmapped.col <- sample(1000:9000, length(melted), replace = TRUE)
write.table(melted, "~/data/bad-dog-no-matrix.tsv",
            row.names = FALSE,
            col.names = TRUE,
            quote = FALSE,
            sep = "\t")


# Dense matrix path for rna seq
melted <- read.table("~/data/rnaseq.txt", sep = '\t', header = TRUE)
# hack to make this look like a dplyr melted table
melted$variable <- "fpkm"

unmelt <- dcast(melted, sample.id ~ hugo, fill = 0.0)
unmelt <- data.table(unmelt)[, `Var.2`:=NULL]

write.table(unmelt, "~/data/dense-rnaseq.tsv",
            row.names = FALSE,
            col.names = TRUE,
            quote = FALSE,
            sep = "\t")

# Synthesize some fake subject data
subjects <- read.table("~/code/pret/test/resources/systems/candel/small-reference-import/processed/subjects.txt",
                       sep = "\t",
                       header = TRUE)

# Need samples from dense-rnaseq as ref
samples <- unique(unmelt$sample.id)
age <- sample(25:90, length(samples), replace = TRUE)
subj.ids <- seq(1:length(samples))
subj.ids <- paste0("SUBJ", subj.ids)
sex <- sample(c("M", "F"), length(samples), replace = TRUE)
race <- sample(unique(subjects$RACE), length(samples), replace = TRUE)
ethnic <- sample(unique(subjects$ETHNIC), length(samples), replace = TRUE)
samples.df <- data.frame(subj.ids, samples, sex, age, race, ethnic)

write.table(samples.df, '~/code/pret/test/resources/matrix/samples.tsv',
            row.names = FALSE,
            col.names = TRUE,
            quote = FALSE,
            sep = '\t')


# make single cells file
tab <- read.table('~/code/pret/test/resources/matrix/short-processed-counts.tsv',
                  sep = '\t',
                  header = TRUE)

# we make a new file just to get rid of duplicates from barcode x gene product
barcodes <- unique(tab$barcode)

write.table(barcodes, '~/code/pret/test/resources/matrix/cell-barcodes.tsv',
            sep = '\t',
            row.names = FALSE,
            col.names = TRUE,
            quote = FALSE)

# remap genes
# -- this db chosen b/c latest schema and ref data, no other reason
set_dbname("aerosmith-pici0009-t1d")
all.genes <- wick::get_all_genes()
tab$hugo <- map_gene_symbols(all.genes, tab$hugo)
cleaned <- na.omit(tab)

# rewrite cleaned single cell file
write.table(cleaned,
            '~/code/pret/test/resources/matrix/short-processed-counts.tsv',
            row.names = FALSE,
            col.names = TRUE,
            quote = FALSE,
            sep = "\t")

# remap for rna seq as measurement matrix also
melted <- read.table("~/data/rnaseq.txt", sep = '\t', header = TRUE)
# hack to make this look like a dplyr melted table
melted$variable <- "fpkm"
# remap now
melted$hugo <- map_gene_symbols(all.genes, melted$hugo)
cleaned <- na.omit(melted)

unmelt <- dcast(cleaned, sample.id ~ hugo, fill = 0.0)

# didn't need this clean step this time?
# unmelt <- data.table(cleaned)[, `Var.2`:=NULL]

write.table(unmelt,
            '~/code/pret/test/resources/matrix/dense-rnaseq.tsv',
            row.names = FALSE,
            col.names = TRUE,
            quote = FALSE,
            sep = "\t")
