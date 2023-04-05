import pyspark.pandas as pd
import numpy as np

def strc_to_numc(data,col_name):
    col_data=data[col_name].copy()
    label_encode={None}
    if col_data.isnull().sum()>0:
        print(col_name,' has null values. ','Please replace the null values.')
        replace_or_not=input('Do you want to the system to replace the null values and then encode the values[Y/n]?: ')
        if replace_or_not.lower()=='y':
            new_str=input('Enter the new value or press ENTER to assign the default value(missing): ')
            if len(new_str)==0:
                new_str='Missing'
            print('Replace the values')
            col_data=fill_null_str(data,col_name,replace_str_with=new_str)
            #encoding
            keys=np.unique(col_data.sort_values().to_numpy())
            values=range(len(keys))
            label_encode=dict(zip(keys,values))
            encoded_values=col_data.map(label_encode)
            return encoded_values,label_encode
        elif replace_or_not.lower()=='n':
            print('Do not replace the values')
            return col_data,label_encode
        else:
            print('else statement in strc_to_numc. Not executing the code')
    else:
        #encoding
        print(col_name,' executed')
        keys=np.unique(col_data.sort_values().to_numpy())
        values=range(len(keys))
        label_encode=dict(zip(keys,values))
        encoded_values=col_data.map(label_encode)
        return encoded_values,label_encode


#converting all the string columns in the dataframe to numeric columns
def strd_to_numd(data_frame):
    dictionary_values={}
    data=data_frame.copy()
    for col_name in data.columns:
        if data[col_name].dtype=='O':
            try:
                converted_values=strc_to_numc(data,col_name)
                data[col_name]=converted_values[0]
                dictionary_values[col_name]=converted_values[1]#encoded values
            except:
                data[col_name]=data[col_name].astype(str)
                converted_values=strc_to_numc(data,col_name)
                data[col_name]=converted_values[0]
                dictionary_values[col_name]=converted_values[1]#encoded values
        else:
            pass
    return data,dictionary_values

#replacing the null values
def fill_null_str(data_frame,col_name,replace_str_with='Missing'):
    col_data=data_frame[col_name].copy()
    replaced_col=col_data.fillna(replace_str_with)
    return replaced_col

def fill_null_num(data_frame,col_name,impute_type='mode'):
    #print(col_name)
    if impute_type=='mode':
        replaced_col=data_frame[col_name].fillna(data_frame[col_name].mode()[0].astype(float))
        return replaced_col
    elif impute_type=='median':
        replaced_col=data_frame[col_name].fillna(data_frame[col_name].median())
        return replaced_col
    elif impute_type=='mean':
        replaced_col=data_frame[col_name].fillna(data_frame[col_name].mean())
        return replaced_col
    else:
        print('Impute type is not valid')

#add an option to replace the null values of string type column with the label repeated more.
def fill_null_data(data_frame,fill_null_sc='Missing',nc_impute_type='mode'):
    data=data_frame.copy()
    for col_name in data.columns:
        if data_frame[col_name].isnull().sum()>0:
            try:
                if data[col_name].dtype=='O':
                    data[col_name]=fill_null_str(data,col_name,fill_null_sc)
                else:
                    data[col_name]=fill_null_num(data,col_name,nc_impute_type)
            except:
                print("Couldn't replace the null values for ",col_name)
        else:
            pass
    return data
