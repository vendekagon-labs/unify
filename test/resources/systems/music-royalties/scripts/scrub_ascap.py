import pandas as pd
from glob import glob

files = glob("data/raw/ascap/*.csv")
for file in files:
    df = pd.read_csv(file)
    # Two file types to handle
    if '$ Amount' in df.columns:
        sub = df[['Party Name', '$ Amount', 'Distribution Date', 'Territory',
                  'Work Title', 'Revenue Class Description', 'Member Share',
                  'Series Name', 'Program Name']]
    else:
        sub = df[['Party Name', 'Series or Film/Attraction', 'DistributionYear',
                  'Distribution Quarter', 'Party Name',
                  'Performance Source/Broadcast Medium', 'Program Name',
                  'Dollars', 'Performing Artist', 'Composer Name',
                  'Territory', 'Work Title']]
    sub.to_csv(f"{file[:-4]}.tsv", sep='\t', na_rep='NA', index=False)

